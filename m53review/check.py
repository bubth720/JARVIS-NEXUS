"""Additive M53 review guard: every historical tracked blob and mode is frozen."""
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BASE = "be70faa41d202d77eb8cf0cc5c831e10cfb020b5"
WORKFLOW = ".github/workflows/m53-readonly-review.yml"

def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)

def check():
    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], cwd=ROOT, check=True)
    frozen = {}
    for entry in git("ls-tree", "-rz", BASE).split(b"\0"):
        if not entry:
            continue
        meta, name = entry.split(b"\t")
        mode, kind, oid = meta.decode().split()
        path = name.decode()
        assert kind == "blob", path
        frozen[path] = (mode, oid)
        current = ROOT / path
        assert current.is_file() and not current.is_symlink(), path
        assert git("hash-object", "--", path).decode().strip() == oid, "Historical bytes changed: " + path
        assert bool(current.stat().st_mode & 0o111) == (mode == "100755"), "Historical mode changed: " + path
    names = set(git("ls-files", "--cached", "--others", "--exclude-standard").decode().splitlines())
    assert set(frozen) <= names, "Historical file deleted"
    added = sorted(names - frozen.keys())
    assert added, "Review missing"
    assert all(p == WORKFLOW or p.startswith("m53review/") for p in added), added
    for p in added:
        path = ROOT / p
        assert not path.is_symlink() and path.is_file() and not (path.stat().st_mode & 0o111), p
    corpus = json.loads((ROOT / "m53review/corpus.json").read_text())
    assert corpus["status"] == "PUBLIC_SYNTHETIC_TEST_DATA"
    for record in corpus["files"]:
        data = (ROOT / "m53review/corpus" / record["name"]).read_bytes()
        assert len(data) == record["bytes"]
        assert hashlib.sha256(data).hexdigest() == record["sha256"]
        assert record["uri"] == "fixture:m53/" + record["name"]
        assert len(data) <= 4096
        data.decode("utf-8", errors="strict")
    return {"status": "PASS", "base": BASE, "historical_blobs_preserved": len(frozen),
            "head": git("rev-parse", "HEAD").decode().strip(),
            "tree": git("rev-parse", "HEAD^{tree}").decode().strip(),
            "additions": added, "android_saf": "NOT_RUN", "llm": "NOT_RUN",
            "live_core_interface": "NOT_RUN", "services_runtime": "NOT_RUN"}

if __name__ == "__main__":
    print(json.dumps(check(), indent=2))
