"""Synthetic contract tests are not libzim/phone/real-Wikipedia qualification."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import sys
import tempfile
import time
import types
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
WORKER = ROOT / "adapters/zim/nexus_zim.py"
spec = importlib.util.spec_from_file_location("nexus_zim", WORKER)
zim = importlib.util.module_from_spec(spec)
spec.loader.exec_module(zim)


def synthetic_header(path, size=128):
    """Header-only invalid native archive for catalogue I/O tests, NEVER a ZIM fixture."""
    header = bytearray(80)
    header[:4] = b"ZIM\x04"
    header[8:24] = bytes(range(16))
    struct.pack_into("<Q", header, 72, size - 16)
    with open(path, "wb") as f:
        f.write(header)
        f.truncate(size)


class FakeReader:
    calls = 0
    def __init__(self, fd):
        assert os.fstat(fd).st_size >= 80

    def search(self, query, limit):
        return [{"path": "Paris", "title": "Paris"}][:limit]

    def article(self, path):
        FakeReader.calls += 1
        if path == "absent":
            raise KeyError(path)
        if path == "broken":
            raise zim.Failure("CORRUPT")
        text = "Paris : encyclopédie synthétique."
        digest = hashlib.sha256(text.encode()).hexdigest()
        return dict(title="Paris", path=path, text=text, rawSha256=digest, textSha256=digest)


class CatalogueTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.archive = self.root / "wikipedia_fr_all_maxi_2026-05 (3).zim"
        synthetic_header(self.archive)
        self.database = self.root / "catalog.db"
        self.catalog = zim.OfflineArchiveCatalog(self.database, FakeReader)
        self.key = self.catalog.register(str(self.archive))["id"]

    def tearDown(self):
        self.catalog.close()
        self.temp.cleanup()

    def test_restart_reference_and_readonly_remove(self):
        before = self.archive.read_bytes()
        info = self.archive.stat()
        self.catalog.close()
        self.catalog = zim.OfflineArchiveCatalog(self.database, FakeReader)
        self.assertEqual(self.key, self.catalog.list()[0]["id"])
        self.assertEqual("Paris", self.catalog.search(self.key, "Par")[0]["title"])
        self.catalog.article(self.key, "Paris")
        self.catalog.remove(self.key)
        self.assertEqual([], self.catalog.list())
        self.assertEqual(before, self.archive.read_bytes())
        self.assertEqual(info.st_mtime_ns, self.archive.stat().st_mtime_ns)

    def test_cache_and_provenance(self):
        FakeReader.calls = 0
        first = self.catalog.article(self.key, "Paris")
        self.catalog.article(self.key, "Paris")
        self.assertEqual(1, FakeReader.calls)
        self.assertEqual(self.key, first["archive"]["fingerprint"])
        self.assertFalse(first["archive"]["checksumVerified"])
        self.assertEqual(hashlib.sha256(first["text"].encode()).hexdigest(), first["textSha256"])
        first["text"] = "caller mutation"
        self.assertNotEqual(first["text"], self.catalog.article(self.key, "Paris")["text"])

    def test_revoked_access_is_checked_before_cache(self):
        self.catalog.article(self.key, "Paris")
        with patch.object(zim.os, "open", side_effect=PermissionError):
            with self.assertRaises(PermissionError):
                self.catalog.article(self.key, "Paris")

    def test_missing_and_replaced_archive(self):
        self.archive.unlink()  # only our tiny synthetic fixture
        with self.assertRaises(FileNotFoundError):
            self.catalog.article(self.key, "Paris")
        synthetic_header(self.archive, 256)
        with self.assertRaisesRegex(zim.Failure, "SOURCE_CHANGED"):
            self.catalog.article(self.key, "Paris")

    def test_corruption(self):
        self.archive.write_bytes(b"bad")
        with self.assertRaisesRegex(zim.Failure, "CORRUPT"):
            self.catalog.article(self.key, "Paris")

    def test_bad_checksum_offset(self):
        data = bytearray(self.archive.read_bytes())
        struct.pack_into("<Q", data, 72, 2**63)
        self.archive.write_bytes(data)
        with self.assertRaisesRegex(zim.Failure, "CORRUPT"):
            self.catalog.register(str(self.archive))

    def test_paths_and_limits(self):
        for path in ("../Paris", "/Paris", "a/../Paris", "a\\Paris", "x\x00"):
            with self.subTest(path=path), self.assertRaises(zim.Failure):
                self.catalog.article(self.key, path)
        for limit in (0, 21):
            with self.assertRaisesRegex(zim.Failure, "INVALID_INPUT"):
                self.catalog.search(self.key, "Paris", limit)
        with self.assertRaises(zim.Failure):
            self.catalog.search(self.key, "x" * 257)

    def test_saF_unsupported(self):
        with self.assertRaisesRegex(zim.Failure, "UNSUPPORTED"):
            self.catalog.register("content://documents/zim")

    def test_symlink_rejected(self):
        link = self.root / "link.zim"
        link.symlink_to(self.archive)
        with self.assertRaises(OSError):
            self.catalog.register(str(link))

    def test_reader_errors(self):
        with self.assertRaises(KeyError):
            self.catalog.article(self.key, "absent")
        with self.assertRaisesRegex(zim.Failure, "CORRUPT"):
            self.catalog.article(self.key, "broken")

    def test_sparse_64_bit_seek_and_bounded_catalogue(self):
        # 55.44 decimal GB, sparse synthetic file: no Wikipedia data or copy.
        large = self.root / "synthetic-sparse.bin"
        synthetic_header(large, 55_440_000_000)
        start = time.monotonic()
        value = self.catalog.register(str(large))
        elapsed = time.monotonic() - start
        self.assertEqual(55_440_000_000, value["bytes"])
        self.assertLess(large.stat().st_blocks * 512, 1024 * 1024)
        self.assertLess(self.database.stat().st_size, 1024 * 1024)
        print(f"EVIDENCE sparse64 bytes={value['bytes']} allocated={large.stat().st_blocks * 512} register_seconds={elapsed:.6f}")

    def test_cache_byte_and_entry_bounds(self):
        cache = zim.BoundedCache(80, 2)
        for n in range(100):
            cache.put(n, {"x": "a" * 20})
            self.assertLessEqual(cache.bytes, 80)
            self.assertLessEqual(len(cache.data), 2)
        self.assertIsNone(cache.get(0))
        cache.put("too big", {"x": "a" * 100})
        self.assertIsNone(cache.get("too big"))

    def test_no_network_contract(self):
        with patch.object(socket, "socket", side_effect=AssertionError("network forbidden")):
            self.catalog.search(self.key, "Paris")
            self.catalog.article(self.key, "Paris")

    def test_cli_restart_and_typed_errors(self):
        def run(*args):
            return subprocess.run([sys.executable, str(WORKER), "--catalog", str(self.database), *args],
                                  capture_output=True, text=True, timeout=10)
        self.assertEqual(self.key, json.loads(run("list").stdout)["value"][0]["id"])
        failed = run("register", "content://revoked/zim")
        self.assertEqual(2, failed.returncode)
        self.assertEqual("UNSUPPORTED", json.loads(failed.stdout)["status"])

    def test_missing_native_backend_is_unsupported(self):
        with patch.dict(sys.modules, {"libzim": None}):
            with zim.source(str(self.archive)) as (fd, _):
                with self.assertRaisesRegex(zim.Failure, "UNSUPPORTED"):
                    zim.LibzimReader(fd)


@unittest.skipUnless(os.environ.get("NEXUS_ZIM_TEST_ARCHIVE"), "real archive not supplied; no native qualification")
class RealArchiveTests(unittest.TestCase):
    def test_native_search_article_and_source_unchanged(self):
        archive = Path(os.environ["NEXUS_ZIM_TEST_ARCHIVE"])
        before = archive.stat()
        with tempfile.TemporaryDirectory() as tmp:
            catalog = zim.OfflineArchiveCatalog(Path(tmp) / "catalog.db")
            try:
                key = catalog.register(str(archive))["id"]
                hits = catalog.search(key, "Paris", 5)
                self.assertTrue(hits)
                article = catalog.article(key, hits[0]["path"])
                self.assertTrue(article["text"])
                self.assertEqual(key, article["archive"]["fingerprint"])
            finally:
                catalog.close()
        after = archive.stat()
        self.assertEqual((before.st_ino, before.st_size, before.st_mtime_ns), (after.st_ino, after.st_size, after.st_mtime_ns))


class NativeApiContractTests(unittest.TestCase):
    """Injected API objects test adapter calls, not native decoding."""
    def setUp(self):
        self.item = types.SimpleNamespace(size=58, mimetype="text/html", content=b"<h1>Paris</h1><script>bad()</script><p>Ville</p>")
        self.entry = types.SimpleNamespace(title="Paris", get_item=lambda: self.item)
        self.archive = types.SimpleNamespace(has_title_index=True, get_entry_by_path=lambda p: self.entry)
        self.reader = zim.LibzimReader.__new__(zim.LibzimReader)
        self.reader.archive = self.archive
        self.reader.searcher = types.SimpleNamespace(suggest=lambda q: types.SimpleNamespace(get_results=lambda start, count: ["Paris"][:count]))

    def test_real_api_names_and_inert_text_extraction(self):
        self.assertEqual("Paris", self.reader.search("Par", 1)[0]["title"])
        value = self.reader.article("Paris")
        self.assertIn("Ville", value["text"])
        self.assertNotIn("bad()", value["text"])
        self.assertEqual(hashlib.sha256(self.item.content).hexdigest(), value["rawSha256"])

    def test_oversized_item_rejected_before_content_read(self):
        class Oversized:
            size = zim.MAX_ARTICLE + 1
            @property
            def content(self):
                raise AssertionError("content must not be read")
        self.item = Oversized()
        with self.assertRaisesRegex(zim.Failure, "LIMIT_EXCEEDED"):
            self.reader.article("Paris")

    def test_missing_title_index_is_explicit(self):
        self.archive.has_title_index = False
        with self.assertRaisesRegex(zim.Failure, "UNSUPPORTED"):
            self.reader.search("Par", 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
