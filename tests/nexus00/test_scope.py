"""NEXUS-00 successor guard: positive and negative cases (synthetic trees only)."""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('scope', ROOT / 'tools' / 'check-nexus00-scope.py')
scope = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scope)


def oid(data):
    return hashlib.sha1(b'blob %d\0' % len(data) + data).hexdigest()


class ScopeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.files = {'src/Quota.kt': b'limit = 2 MiB\n', '.github/workflows/nex-07.yml': b'old\n'}
        for name, data in self.files.items():
            (self.root / name).parent.mkdir(parents=True, exist_ok=True)
            (self.root / name).write_bytes(data)
        self.frozen = {n: ('100644', oid(d)) for n, d in self.files.items()}
        self.replacements = {'.github/workflows/nex-07.yml': b'old\n'}

    def tearDown(self):
        self.temp.cleanup()

    def check(self, actual, additions=()):
        scope.verify_delta(self.root, self.frozen, actual, set(additions), self.replacements,
                           lambda n: oid((self.root / n).read_bytes()))

    def test_unchanged_tree_passes(self):
        self.check(list(self.files))

    def test_declared_addition_passes(self):
        (self.root / 'new.md').write_text('x')
        self.check(list(self.files) + ['new.md'], ['new.md'])

    def test_undeclared_extra_file_fails(self):
        (self.root / 'rogue.py').write_text('x')
        with self.assertRaisesRegex(ValueError, 'Undeclared files: rogue.py'):
            self.check(list(self.files) + ['rogue.py'])

    def test_mutated_historical_file_fails(self):
        (self.root / 'src/Quota.kt').write_bytes(b'limit = unlimited\n')
        with self.assertRaisesRegex(ValueError, 'Historical bytes changed: src/Quota.kt'):
            self.check(list(self.files))

    def test_deleted_historical_file_fails(self):
        (self.root / 'src/Quota.kt').unlink()
        with self.assertRaisesRegex(ValueError, 'Historical file deleted'):
            self.check(['.github/workflows/nex-07.yml'])

    def test_workflow_drift_beyond_declared_lane_fails(self):
        (self.root / '.github/workflows/nex-07.yml').write_bytes(b'old\n# disable quota\n')
        with self.assertRaisesRegex(ValueError, 'Declared replacement differs'):
            self.check(list(self.files))

    def test_executable_addition_fails(self):
        p = self.root / 'run.sh'
        p.write_text('x'); p.chmod(0o755)
        with self.assertRaisesRegex(ValueError, 'regular non-executable'):
            self.check(list(self.files) + ['run.sh'], ['run.sh'])

    def test_expected_workflow_rewrites_exactly_both_default_lanes(self):
        lane = scope.OLD_LANE
        out = scope.expected_workflow((lane + 'x\n' + lane).encode()).decode()
        self.assertEqual(out.count(scope.NEW_LANE), 2)
        with self.assertRaises(ValueError):
            scope.expected_workflow(lane.encode())

    def test_quota_files_are_frozen_not_additions(self):
        self.assertFalse(any('SelectedDocumentImporter.kt' in a or 'ImportCoordinator' in a for a in scope.ADDITIONS))


if __name__ == '__main__':
    unittest.main()
