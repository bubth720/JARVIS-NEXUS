import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('prefusion', Path(__file__).resolve().parents[2] / 'tools/check-prefusion.py')
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class PreservationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'runtime.kt').write_bytes(b'frozen')
        (self.root / 'workflow.yml').write_bytes(b'reviewed route')
        self.frozen = {'runtime.kt': b'frozen', 'workflow.yml': b'old route'}

    def check(self, extra=()):
        guard.verify_files(self.root, self.frozen, list(self.frozen) + list(extra), {'report.md'},
                           {'workflow.yml': b'reviewed route'})

    def test_frozen_sources_and_reviewed_route(self):
        (self.root / 'report.md').write_text('BLOCKED')
        self.check(['report.md'])

    def test_runtime_change_refused(self):
        (self.root / 'runtime.kt').write_bytes(b'activation')
        with self.assertRaises(ValueError): self.check()

    def test_baseline_deletion_refused(self):
        (self.root / 'runtime.kt').unlink()
        with self.assertRaises(ValueError): self.check()

    def test_new_runtime_refused(self):
        (self.root / 'new.kt').write_bytes(b'activation')
        with self.assertRaises(ValueError): self.check(['new.kt'])

    def test_symlink_refused_even_with_identical_bytes(self):
        (self.root / 'runtime.kt').unlink()
        (self.root / 'copy').write_bytes(b'frozen')
        (self.root / 'runtime.kt').symlink_to('copy')
        with self.assertRaises(ValueError): self.check()

    def test_other_workflow_change_refused(self):
        (self.root / 'workflow.yml').write_bytes(b'skip tests')
        with self.assertRaises(ValueError): self.check()
