import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("m50_guard", ROOT / "tools/check-m50-s1.py")
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)

# Two original guard blocks and sentinel commands; no production commands removed.
STEP = b"""      - uses: actions/checkout@reviewed-pin
        with:
          fetch-depth: 0
          persist-credentials: false
      - name: Check the exact reviewed source scope
        env:
          LOT3_REVIEW: old-reviewed-condition
        run: |
          if [ "$LOT3_REVIEW" = "true" ]; then
            python3 tools/check-prefusion-lot3.py
          else
            python3 tools/check-prefusion.py
          fi
"""
ORIGINAL = (b"permissions:\n  contents: read\njobs:\n  jvm:\n    steps:\n" + STEP +
            b"      - run: test-jvm\n  android-host:\n    steps:\n" + STEP +
            b"      - run: test-android\n")

class ScopeTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.frozen = {
            "runtime.kt": b"frozen", "AndroidManifest.xml": b"no-new-permissions",
            guard.WORKFLOW: ORIGINAL,
        }
        self.actual = set(self.frozen) | guard.ADDED
        for name in self.actual:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(self.frozen.get(name, b"review"))
        (self.root / guard.WORKFLOW).write_bytes(guard.expected_workflow(ORIGINAL))
    def check(self):
        guard.verify_files(self.root, self.frozen, self.actual, ORIGINAL, b"review")
    def test_exact_reviewed_scope(self):
        self.check()
    def test_runtime_change_rejected(self):
        (self.root / "runtime.kt").write_bytes(b"activate")
        with self.assertRaises(ValueError): self.check()
    def test_manifest_permission_change_rejected(self):
        (self.root / "AndroidManifest.xml").write_bytes(b"INTERNET")
        with self.assertRaises(ValueError): self.check()
    def test_deletion_rejected(self):
        (self.root / "runtime.kt").unlink()
        self.actual.remove("runtime.kt")
        with self.assertRaises(ValueError): self.check()
    def test_symlink_rejected(self):
        (self.root / "runtime.kt").unlink()
        (self.root / "runtime.kt").symlink_to("AndroidManifest.xml")
        with self.assertRaises(ValueError): self.check()
    def test_undeclared_runtime_rejected(self):
        self.actual.add("new-runtime.kt")
        (self.root / "new-runtime.kt").write_bytes(b"activation")
        with self.assertRaises(ValueError): self.check()
    def test_unreviewed_workflow_rejected(self):
        (self.root / guard.WORKFLOW).write_bytes(guard.expected_workflow(ORIGINAL) + b"# skip")
        with self.assertRaises(ValueError): self.check()
    def test_new_workflow_rejected(self):
        name = ".github/workflows/unreviewed.yml"
        self.actual.add(name)
        (self.root / name).write_bytes(b"skip")
        with self.assertRaises(ValueError): self.check()
    def test_published_mission_cannot_change(self):
        (self.root / "missions/M50-INTEGRATION-2026-10-29.md").write_bytes(b"activate")
        with self.assertRaises(ValueError): self.check()
    def test_required_review_file_cannot_disappear(self):
        self.actual.remove("docs/m50/M50-S1-CI.md")
        with self.assertRaises(ValueError): self.check()
    def test_only_exact_event_base_head_eligible(self):
        self.assertTrue(guard.eligible("pull_request", guard.BASE_BRANCH, guard.HEAD_BRANCH, "owner/repo", "owner/repo"))
        for event, base, head in (
            ("push", guard.BASE_BRANCH, guard.HEAD_BRANCH),
            ("workflow_dispatch", guard.BASE_BRANCH, guard.HEAD_BRANCH),
            ("pull_request", "main", guard.HEAD_BRANCH),
            ("pull_request", guard.BASE_BRANCH, "codex/m50-s1-other"),
            ("pull_request_target", guard.BASE_BRANCH, guard.HEAD_BRANCH),
        ):
            self.assertFalse(guard.eligible(event, base, head, "owner/repo", "owner/repo"))
    def test_fork_with_same_head_name_is_rejected(self):
        self.assertFalse(guard.eligible("pull_request", guard.BASE_BRANCH, guard.HEAD_BRANCH,
                                       "owner/repo", "attacker/repo"))
    def test_parent_directory_symlink_rejected(self):
        (self.root / "missions").rename(self.root / "moved-missions")
        (self.root / "missions").symlink_to(self.root / "moved-missions", target_is_directory=True)
        with self.assertRaises(ValueError): self.check()
    def test_changed_index_mode_rejected(self):
        modes = {name: "100644" for name in self.actual}
        modes["runtime.kt"] = "100755"
        with self.assertRaises(ValueError):
            guard.verify_modes(self.root, {name: "100644" for name in self.frozen}, modes, self.actual)
    def test_changed_worktree_executable_mode_rejected(self):
        (self.root / "runtime.kt").chmod(0o700)
        with self.assertRaises(ValueError):
            guard.verify_modes(self.root, {name: "100644" for name in self.frozen},
                               {name: "100644" for name in self.actual}, self.actual)
    def test_unchanged_modes_accepted(self):
        guard.verify_modes(self.root, {name: "100644" for name in self.frozen},
                           {name: "100644" for name in self.actual}, self.actual)
    def test_route_conditions_and_commands_are_exact(self):
        after = guard.expected_workflow(ORIGINAL)
        self.assertEqual(after.count(guard.M50_CONDITION.encode()), 4)
        self.assertEqual(after.count(b"github.event.pull_request.head.sha || github.ref"), 2)
        for old, new in (
            (guard.M50_CONDITION.encode(), b"true"),
            (b"contents: read", b"contents: write"), (b"test-jvm", b"echo skipped"),
            (b"github.event.pull_request.head.sha", b"github.sha"),
            (b"github.event.pull_request.head.repo.full_name == github.repository", b"true"),
            (b"test-android", b"echo skipped"),
            (b"python3 tools/check-prefusion.py", b"true"),
            (b"python3 tools/check-prefusion-lot3.py", b"true"),
        ):
            with self.assertRaises(ValueError):
                guard.verify_workflow(ORIGINAL, after.replace(old, new))
    def test_unexpected_historical_workflow_rejected(self):
        with self.assertRaises(ValueError):
            guard.expected_workflow(ORIGINAL + ORIGINAL)

if __name__ == "__main__":
    unittest.main()
