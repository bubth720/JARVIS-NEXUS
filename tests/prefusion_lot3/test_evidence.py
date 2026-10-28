import importlib.util
from pathlib import Path
import tempfile
import unittest
import os
import subprocess

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

    def test_only_public_fixture_line_is_selected_from_stack(self):
        self.xml('<testsuite tests="1" failures="1"><testcase classname="fr.jarvis.nexus.prefusion.lab.Lot3QualificationTest" name="picker"><failure>java.lang.AssertionError\nat fr.jarvis.nexus.prefusion.lab.Lot3QualificationTest.choose(Lot3QualificationTest.java:99)\nat private.Secret.notPublished(Secret.java:42)</failure></testcase></testsuite>')
        detail = evidence.summarize(self.folder, 1)['details'][0]
        self.assertTrue(detail.endswith('[fixture line 99]'))
        self.assertNotIn('Secret', detail)

    def test_skips_do_not_qualify(self):
        self.xml('<testsuite tests="5" skipped="1"/>')
        self.assertFalse(evidence.summarize(self.folder, 5)['complete'])

    def test_invalid_negative_count(self):
        self.xml('<testsuite tests="-1"/>')
        with self.assertRaises(ValueError):
            evidence.summarize(self.folder, 5)

    def runner(self, serial, api):
        shim = self.folder / 'adb'
        calls = self.folder / 'calls'
        shim.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "' + str(calls) + '"\n'
                        + 'if [ "$1" = get-serialno ]; then echo ' + serial + '\nelse echo ' + str(api) + '\nfi\n')
        shim.chmod(0o755)
        env = dict(os.environ, PATH=str(self.folder) + os.pathsep + os.environ['PATH'])
        result = subprocess.run(['bash', str(ROOT / 'tools/run-prefusion-lot3-emulator.sh')],
                                cwd=self.folder, env=env, capture_output=True, text=True)
        return result, calls.read_text()

    def test_personal_device_refused_before_install_or_log_read(self):
        result, calls = self.runner('personal-phone', 24)
        self.assertEqual(result.returncode, 2)
        self.assertEqual(calls.splitlines(), ['get-serialno'])
        self.assertNotIn('install', calls)
        self.assertFalse((self.folder / 'labs').exists())

    def test_unreviewed_emulator_api_refused_before_install(self):
        result, calls = self.runner('emulator-5554', 23)
        self.assertEqual(result.returncode, 2)
        self.assertNotIn('install', calls)
        self.assertFalse((self.folder / 'labs').exists())
