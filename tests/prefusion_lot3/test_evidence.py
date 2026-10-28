import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('lot3_evidence', ROOT / 'tools/prefusion-lot3-evidence.py')
evidence = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evidence)


class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)

    def xml(self, text):
        (self.folder / 'TEST-synthetic.xml').write_text(text)

    def test_no_report_never_means_success(self):
        self.assertFalse(evidence.summarize(self.folder, 5)['complete'])

    def test_complete_suite(self):
        self.xml('<testsuite tests="5" failures="0" errors="0" skipped="0"/>')
        self.assertTrue(evidence.summarize(self.folder, 5)['complete'])

    def test_failure_names_and_bounded_first_line_without_stack(self):
        self.xml('<testsuite tests="1" failures="1"><testcase classname="fr.jarvis.nexus.prefusion.lab.Lot3QualificationTest" name="picker"><failure>Assertion first line\nprivate stack must not appear</failure></testcase></testsuite>')
        value = evidence.summarize(self.folder, 1)
        self.assertFalse(value['complete'])
        self.assertEqual(value['details'], ['fr.jarvis.nexus.prefusion.lab.Lot3QualificationTest.picker: Assertion first line'])

    def test_foreign_suite_details_not_published(self):
        self.xml('<testsuite tests="1" failures="1"><testcase classname="other.Package" name="private"><failure message="private"/></testcase></testsuite>')
        self.assertEqual(evidence.summarize(self.folder, 1)['details'], [])

    def test_skips_do_not_qualify(self):
        self.xml('<testsuite tests="5" skipped="1"/>')
        self.assertFalse(evidence.summarize(self.folder, 5)['complete'])

    def test_invalid_negative_count(self):
        self.xml('<testsuite tests="-1"/>')
        with self.assertRaises(ValueError):
            evidence.summarize(self.folder, 5)
