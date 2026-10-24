"""Read-only Linux libzim adapter. No downloads, writers, HTML rendering or network.

Native operations must run in a resource-limited host process for untrusted ZIMs.
The standard-library catalogue is independently usable when libzim is absent.
"""
import argparse
import base64
from collections import OrderedDict
from contextlib import contextmanager
import hashlib
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import sqlite3
import stat
import struct
import sys
from urllib.parse import quote

MAX_ARTICLE = 1024 * 1024
MAX_ARCHIVES = 32
MAX_CACHE = 4 * 1024 * 1024


class Failure(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def require(condition, code="INVALID_INPUT"):
    if not condition:
        raise Failure(code)


def safe_entry(path):
    require(isinstance(path, str) and 0 < len(path) <= 4096)
    require(not path.startswith(("/", "\\")) and "\\" not in path)
    require(all(part not in (".", "..") for part in path.split("/")))
    require(not any(ord(c) < 32 for c in path))
    # This is an internal key, never a filesystem path or URL to fetch.
    return path


@contextmanager
def source(path):
    require(isinstance(path, str) and 0 < len(path) <= 4096)
    if path.startswith("content:"):
        raise Failure("UNSUPPORTED")  # no SAF provider or JNI in Linux worker
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        info = os.fstat(fd)
        require(stat.S_ISREG(info.st_mode), "UNSUPPORTED")
        require(info.st_size >= 80, "CORRUPT")
        # Explicit high-offset read exercises seekability without reading 55 GB.
        require(len(os.pread(fd, 1, info.st_size - 1)) == 1, "CORRUPT")
        header = os.pread(fd, 80, 0)
        require(header[:4] == b"ZIM\x04", "CORRUPT")
        checksum_pos = struct.unpack_from("<Q", header, 72)[0]
        require(80 <= checksum_pos <= info.st_size - 16, "CORRUPT")
        stored = os.pread(fd, 16, checksum_pos).hex()
        identity = {
            "uuid": header[8:24].hex(), "bytes": info.st_size,
            "storedChecksum": stored, "checksumVerified": False,
            "headerSha256": hashlib.sha256(header).hexdigest(),
        }
        # Not an archive SHA-256 or proof of publisher authenticity.
        identity["fingerprint"] = hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()
        yield fd, identity
    finally:
        os.close(fd)


class TextExtractor(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.hidden = 0
        self.parts = []

    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style"):
            self.hidden += 1
        elif tag in ("p", "br", "div", "li", "h1", "h2", "h3") and not self.hidden:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if tag in ("script", "style"):
            self.hidden = max(0, self.hidden - 1)

    def handle_data(self, data):
        if not self.hidden:
            self.parts.append(data)


class BoundedCache:
    def __init__(self, budget=MAX_CACHE, entries=16):
        require(0 <= budget <= MAX_CACHE and 1 <= entries <= 64)
        self.budget, self.entries = budget, entries
        self.data = OrderedDict()
        self.bytes = 0

    def get(self, key):
        value = self.data.get(key)
        if value is not None:
            self.data.move_to_end(key)
            return json.loads(value)

    def put(self, key, value):
        encoded = json.dumps(value, ensure_ascii=False).encode()
        if key in self.data:
            self.bytes -= len(self.data.pop(key))
        if len(encoded) > self.budget:
            return
        self.data[key] = encoded
        self.bytes += len(encoded)
        while self.bytes > self.budget or len(self.data) > self.entries:
            self.bytes -= len(self.data.popitem(last=False)[1])


class LibzimReader:
    """Calls real python-libzim APIs; absence/API mismatch is never simulated."""
    def __init__(self, fd):
        try:
            from libzim.reader import Archive, set_cluster_cache_max_size
            from libzim.suggestion import SuggestionSearcher
        except (ImportError, OSError):
            raise Failure("UNSUPPORTED")
        # Linux descriptor path pins the already opened read-only inode.
        set_cluster_cache_max_size(8 * 1024 * 1024)
        self.archive = Archive(Path(f"/proc/self/fd/{fd}"))
        self.archive.dirent_cache_max_size = 1024
        self.searcher = SuggestionSearcher(self.archive)

    def search(self, query, limit):
        require(self.archive.has_title_index, "UNSUPPORTED")
        return [{"path": safe_entry(p), "title": self.archive.get_entry_by_path(p).title}
                for p in self.searcher.suggest(query).get_results(0, limit)]

    def article(self, path):
        entry = self.archive.get_entry_by_path(safe_entry(path))
        item = entry.get_item()  # libzim resolves redirects, including cycle errors
        require(item.size <= MAX_ARTICLE, "LIMIT_EXCEEDED")
        require(item.mimetype.split(";", 1)[0] in ("text/html", "text/plain"), "UNSUPPORTED")
        raw = bytes(item.content)
        require(len(raw) <= MAX_ARTICLE, "LIMIT_EXCEEDED")
        text = raw.decode("utf-8", "strict")
        if item.mimetype.startswith("text/html"):
            parser = TextExtractor()
            parser.feed(text)
            text = "".join(parser.parts).strip()
        return {"title": entry.title, "path": path, "text": text,
                "rawSha256": hashlib.sha256(raw).hexdigest(),
                "textSha256": hashlib.sha256(text.encode()).hexdigest()}


class OfflineArchiveCatalog:
    """Host-private SQLite, <=32 references; no copied content or external index.

    The host owns this directory and serializes access. Archive permissions are
    checked on every read, even when an article is in the bounded memory cache.
    """
    def __init__(self, database, reader=LibzimReader):
        self.db = sqlite3.connect(database)
        self.db.execute("CREATE TABLE IF NOT EXISTS archives (id TEXT PRIMARY KEY, path TEXT NOT NULL, identity TEXT NOT NULL)")
        self.reader = reader
        self.cache = BoundedCache()

    def close(self):
        self.db.close()

    def register(self, path):
        path = str(Path(path).absolute()) if not path.startswith("content:") else path
        with source(path) as (_, identity):
            key = identity["fingerprint"]
            count = self.db.execute("SELECT count(*) FROM archives WHERE id != ?", (key,)).fetchone()[0]
            require(count < MAX_ARCHIVES, "LIMIT_EXCEEDED")
            with self.db:
                self.db.execute("INSERT OR REPLACE INTO archives VALUES (?, ?, ?)",
                                (key, path, json.dumps(identity, sort_keys=True)))
            return {"id": key, "path": path, **identity}

    def list(self):
        return [{"id": k, "path": p, **json.loads(i)} for k, p, i in
                self.db.execute("SELECT id, path, identity FROM archives ORDER BY id LIMIT 32")]

    def remove(self, key):
        with self.db:
            self.db.execute("DELETE FROM archives WHERE id = ?", (key,))
        self.cache = BoundedCache()

    @contextmanager
    def opened(self, key):
        row = self.db.execute("SELECT path, identity FROM archives WHERE id = ?", (key,)).fetchone()
        require(row is not None, "NOT_FOUND")
        with source(row[0]) as (fd, identity):
            require(identity == json.loads(row[1]), "SOURCE_CHANGED")
            before = os.fstat(fd)
            yield fd, identity
            after = os.fstat(fd)
            require((before.st_size, before.st_mtime_ns, before.st_ctime_ns) ==
                    (after.st_size, after.st_mtime_ns, after.st_ctime_ns), "SOURCE_CHANGED")

    def search(self, key, query, limit=10):
        require(isinstance(query, str) and 0 < len(query.strip()) <= 256)
        require(isinstance(limit, int) and 1 <= limit <= 20)
        with self.opened(key) as (fd, identity):
            results = self.reader(fd).search(query, limit)
            require(len(results) <= limit, "LIMIT_EXCEEDED")
            for hit in results:
                safe_entry(hit["path"])
                require(len(hit["title"]) <= 4096, "LIMIT_EXCEEDED")
                hit["archive"] = identity
            return results

    def article(self, key, path):
        safe_entry(path)
        with self.opened(key) as (fd, identity):
            # Check permission/identity before using a cached item.
            stamp = os.fstat(fd)
            cache_key = (key, path, stamp.st_mtime_ns, stamp.st_ctime_ns)
            result = self.cache.get(cache_key)
            if result is None:
                result = self.reader(fd).article(path)
                require(len(result["text"].encode()) <= MAX_ARTICLE, "LIMIT_EXCEEDED")
                require(len(result["title"]) <= 4096, "LIMIT_EXCEEDED")
                result.update(archive=identity, uri=f"zim://{key}/{quote(path, safe='')}")
                self.cache.put(cache_key, result)
            return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--catalog", required=True)
    p.add_argument("command", choices=("register", "list", "remove", "search", "article", "wire"))
    p.add_argument("args", nargs="*")
    ns = p.parse_args()
    catalog = None
    try:
        catalog = OfflineArchiveCatalog(ns.catalog)
        args = ns.args
        if ns.command == "register" and len(args) == 1:
            value = catalog.register(args[0])
        elif ns.command == "list" and not args:
            value = catalog.list()
        elif ns.command == "remove" and len(args) == 1:
            catalog.remove(args[0]); value = {"removed": args[0]}
        elif ns.command == "search" and len(args) in (2, 3):
            value = catalog.search(args[0], args[1], int(args[2]) if len(args) == 3 else 10)
        elif ns.command in ("article", "wire") and len(args) == 2:
            value = catalog.article(*args)
            if ns.command == "wire":
                print("OK")
                for field in ("title", "path", "text", "rawSha256", "textSha256", "uri"):
                    print(base64.b64encode(value[field].encode()).decode())
                return 0
        else:
            raise Failure("INVALID_INPUT")
        print(json.dumps({"status": "OK", "value": value}, ensure_ascii=False))
        return 0
    except Failure as e:
        code = e.code
    except PermissionError:
        code = "DENIED"
    except (FileNotFoundError, KeyError):
        code = "NOT_FOUND"
    except (AttributeError, ImportError):
        code = "UNSUPPORTED"
    except (RuntimeError, UnicodeError):
        code = "CORRUPT"
    except (OSError, sqlite3.Error):
        code = "STORAGE_FAILURE"
    except ValueError:
        code = "INVALID_INPUT"
    finally:
        if catalog is not None:
            catalog.close()
    print(code if ns.command == "wire" else json.dumps({"status": code}))
    return 2


if __name__ == "__main__":
    sys.exit(main())
