import importlib.util
from pathlib import Path
import subprocess
import unittest
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('lot3_guard',ROOT/'tools/check-prefusion-lot3.py')
guard=importlib.util.module_from_spec(spec);spec.loader.exec_module(guard)
class RoutingScopeTest(unittest.TestCase):
 def original(self):
  return subprocess.check_output(['git','show',guard.BASE+':.github/workflows/nex-07.yml'],cwd=ROOT)
 def test_only_two_exact_guard_steps_are_replaced(self):
  before=self.original();after=guard.expected_workflow(before)
  self.assertEqual(after.count(guard.WORKFLOW_ROUTE.encode()),2)
  self.assertEqual(after.replace(guard.WORKFLOW_ROUTE.encode(),b'      - run: python3 tools/check-prefusion.py\n'),before)
  guard.verify_workflow(before,(ROOT/'.github/workflows/nex-07.yml').read_bytes())
 def test_other_jobs_tests_and_permissions_cannot_change(self):
  before=self.original();after=guard.expected_workflow(before)
  for changed in [after+b'\n# unrelated\n',after.replace(b'contents: read',b'contents: write'),after.replace(b'tests/prefusion -v',b'tests/prefusion_lot3 -v')]:
   with self.assertRaises(ValueError):guard.verify_workflow(before,changed)
 def test_only_exact_lot3_base_and_head_are_eligible(self):
  before=self.original();after=guard.expected_workflow(before)
  for old,new in [(b"github.event_name == 'pull_request'",b'true'),(b"github.base_ref == 'mission/prefusion-lot3-20261027'",b'true'),(b"startsWith(github.head_ref, 'codex/prefusion-lot3-')",b'true')]:
   with self.assertRaises(ValueError):guard.verify_workflow(before,after.replace(old,new))
if __name__=='__main__':unittest.main()
