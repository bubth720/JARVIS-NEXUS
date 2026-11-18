"""Offline mutations only; temporary Git clones never alter the actual sources."""
import copy
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('n01_a3_guard',ROOT/'reuse-r1plus/n01-a3/check_routing_scope.py')
guard=importlib.util.module_from_spec(spec);spec.loader.exec_module(guard)
CONTRACT='nexus-core/src/main/kotlin/fr/jarvis/nexus/rag/RagTypes.kt'
ENV=dict(os.environ,PYTHONDONTWRITEBYTECODE='1')

def run(root,*args):
    return subprocess.run(args,cwd=root,env=ENV,capture_output=True,text=True,timeout=60)

class ScopeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(prefix='n01-a3-template-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.template=Path(cls.temp.name)/'source'
        subprocess.run(['git','clone','--shared','--quiet','--no-checkout',str(ROOT),str(cls.template)],check=True)
        subprocess.run(['git','-C',str(cls.template),'checkout','--quiet','--detach',guard.BASE],check=True)
        for name in sorted(guard.ADDITIONS|{guard.WORKFLOW}):
            dest=cls.template/name;dest.parent.mkdir(parents=True,exist_ok=True)
            shutil.copyfile(ROOT/name,dest)

    def clone(self):
        temp=tempfile.TemporaryDirectory(prefix='n01-a3-mutation-')
        self.addCleanup(temp.cleanup)
        p=Path(temp.name)/'source';shutil.copytree(self.template,p)
        return p

    def rejected(self,root,fragment):
        with self.assertRaisesRegex(ValueError,fragment):guard.check(root)

    def test_current_scope_has_exactly_one_historical_exception(self):
        result=guard.check(ROOT)
        self.assertEqual(337,result['historical_files'])
        self.assertEqual(336,result['historical_files_unchanged'])
        self.assertEqual(guard.WORKFLOW,result['sole_exception'])
        self.assertFalse(result['contracts_changed'])

    def test_independent_baseline_clone_accepts_only_rendered_delta(self):
        self.assertEqual('PASS',guard.check(self.template)['result'])

    def test_contract_mutation_refused(self):
        p=self.clone();f=p/CONTRACT;f.write_bytes(f.read_bytes()+b'\n// unauthorized\n')
        self.rejected(p,'Unauthorized bytes: '+CONTRACT)

    def test_historical_guard_mutation_refused(self):
        p=self.clone();f=p/'reuse-r1plus/check.py';f.write_bytes(f.read_bytes()+b'\n# bypass\n')
        self.rejected(p,'Unauthorized bytes: reuse-r1plus/check.py')

    def test_historical_deletion_refused(self):
        p=self.clone();(p/CONTRACT).unlink();self.rejected(p,'Missing/non-regular historical file')

    def test_historical_mode_change_refused(self):
        p=self.clone();f=p/CONTRACT;f.chmod(f.stat().st_mode|0o111)
        self.rejected(p,'Unauthorized mode: '+CONTRACT)

    def test_identical_historical_symlink_refused(self):
        p=self.clone();f=p/CONTRACT;other=p/'build/synthetic.txt';other.parent.mkdir(exist_ok=True);other.write_bytes(f.read_bytes());f.unlink();f.symlink_to(other)
        self.rejected(p,'Missing/non-regular historical file')

    def test_historical_directory_replacement_refused(self):
        p=self.clone();f=p/CONTRACT;f.unlink();f.mkdir();self.rejected(p,'Missing/non-regular historical file')

    def test_extra_path_outside_laboratory_refused(self):
        p=self.clone();(p/'UNDECLARED.txt').write_text('synthetic');self.rejected(p,'Unexpected additions')

    def test_extra_path_inside_laboratory_refused(self):
        p=self.clone();(p/'reuse-r1plus/n01-a3/UNDECLARED.txt').write_text('synthetic');self.rejected(p,'Unexpected additions')

    def test_missing_new_file_refused(self):
        p=self.clone();(p/'reuse-r1plus/n01-a3/AUTHORIZATION.json').unlink();self.rejected(p,'Unexpected additions')

    def test_new_symlink_refused(self):
        p=self.clone();f=p/'reuse-r1plus/n01-a3/AUTHORIZATION.json';other=p/'build/authorization.txt';other.parent.mkdir(exist_ok=True);other.write_bytes(f.read_bytes());f.unlink();f.symlink_to(other)
        self.rejected(p,'Unsafe addition')

    def test_new_executable_refused(self):
        p=self.clone();f=p/'reuse-r1plus/n01-a3/AUTHORIZATION.json';f.chmod(0o755);self.rejected(p,'Unsafe addition')

    def test_empty_new_file_refused(self):
        p=self.clone();(p/'reuse-r1plus/n01-a3/AUTHORIZATION.json').write_text(' ');self.rejected(p,'Empty addition')

    def mutate_workflow(self,old,new):
        p=self.clone();f=p/guard.WORKFLOW;text=f.read_text();self.assertIn(old,text);f.write_text(text.replace(old,new));self.rejected(p,'Unauthorized bytes: .github/workflows/nex-07.yml')

    def test_write_permission_refused(self):self.mutate_workflow('contents: read','contents: write')
    def test_guard_bypass_refused(self):self.mutate_workflow('python3 reuse-r1plus/n01-a3/check_routing_scope.py --ci-event','true # bypass')
    def test_removed_jvm_tests_refused(self):self.mutate_workflow('test :nexus-cli:installDist',':nexus-cli:installDist')
    def test_removed_android_tests_refused(self):self.mutate_workflow(':nexus-android-store:testDebugUnitTest','')
    def test_unrelated_m50_route_change_refused(self):self.mutate_workflow('python3 tools/check-m50-s1.py','true')
    def test_unrelated_lot3_route_change_refused(self):self.mutate_workflow('python3 tools/check-prefusion-lot3.py','true')
    def test_default_guard_change_refused(self):self.mutate_workflow('python3 tools/check-prefusion.py','true')
    def test_broadened_branch_selector_refused(self):self.mutate_workflow("github.head_ref == 'codex/nexus-n01-a3-routing-20261118'","startsWith(github.head_ref, 'codex/')")
    def test_extra_comment_is_outside_exact_delta(self):self.mutate_workflow('name: NEX-07 isolated quota preflight','name: NEX-07 isolated quota preflight # additional delta')

    def test_renderer_refuses_unexpected_historical_input(self):
        with self.assertRaisesRegex(ValueError,'Unexpected historical checkout'):guard.expected_workflow(b'name: changed\n')

    def test_legacy_guard_still_refuses_approved_workflow_exception(self):
        result=run(self.template,sys.executable,'reuse-r1plus/check.py')
        self.assertNotEqual(0,result.returncode)
        self.assertIn(guard.WORKFLOW,result.stderr)

    def test_historical_guards_are_all_byte_and_mode_identical(self):
        names=guard.git(ROOT,'ls-tree','-r','--name-only',guard.BASE).decode().splitlines()
        guards=[p for p in names if p.endswith('.py') and (Path(p).name.startswith('check') or Path(p).name=='test_scope.py')]
        self.assertGreater(len(guards),5)
        for name in guards:
            self.assertEqual(guard.git(ROOT,'cat-file','--filters',guard.BASE+':'+name),(ROOT/name).read_bytes(),name)
            self.assertEqual(guard.git(ROOT,'ls-tree',guard.BASE,'--',name).split()[0]==b'100755',bool((ROOT/name).stat().st_mode&0o111),name)

class EventTest(unittest.TestCase):
    def setUp(self):
        self.sha='1'*40
        self.event={'repository':{'full_name':guard.REPOSITORY},'pull_request':{
            'base':{'ref':guard.PR_BASE,'sha':guard.PR_BASE_SHA,'repo':{'full_name':guard.REPOSITORY}},
            'head':{'ref':guard.PR_HEADS[1],'sha':self.sha,'repo':{'full_name':guard.REPOSITORY}}}}
        self.env={'GITHUB_EVENT_NAME':'pull_request','GITHUB_REPOSITORY':guard.REPOSITORY,'GITHUB_BASE_REF':guard.PR_BASE,'GITHUB_HEAD_REF':guard.PR_HEADS[1]}

    def check(self,event=None,env=None,sha=None):
        return guard.verify_ci_event(self.event if event is None else event,self.env if env is None else env,self.sha if sha is None else sha)

    def test_reviewed_new_head_accepted(self):self.assertTrue(self.check()['same_repository'])
    def test_original_reuse_head_accepted(self):
        self.event['pull_request']['head']['ref']=guard.PR_HEADS[0];self.env['GITHUB_HEAD_REF']=guard.PR_HEADS[0];self.assertTrue(self.check()['same_repository'])
    def test_fork_refused(self):
        self.event['pull_request']['head']['repo']['full_name']='synthetic/fork'
        with self.assertRaisesRegex(ValueError,'Fork'):self.check()
    def test_other_base_refused(self):
        self.event['pull_request']['base']['ref']='main'
        with self.assertRaisesRegex(ValueError,'Unreviewed PR base'):self.check()
    def test_moved_base_refused(self):
        self.event['pull_request']['base']['sha']='2'*40
        with self.assertRaisesRegex(ValueError,'Unreviewed PR base'):self.check()
    def test_other_head_refused(self):
        self.event['pull_request']['head']['ref']=guard.PR_HEADS[1]+'-unreviewed'
        with self.assertRaisesRegex(ValueError,'Unreviewed PR head'):self.check()
    def test_synthetic_merge_checkout_refused(self):
        with self.assertRaisesRegex(ValueError,'exact PR head'):self.check(sha='2'*40)
    def test_push_event_refused(self):
        self.env['GITHUB_EVENT_NAME']='push'
        with self.assertRaisesRegex(ValueError,'PR event required'):self.check()
    def test_repository_environment_mismatch_refused(self):
        self.env['GITHUB_REPOSITORY']='synthetic/other'
        with self.assertRaisesRegex(ValueError,'Repository mismatch'):self.check()
    def test_repository_payload_mismatch_refused(self):
        self.event['repository']['full_name']='synthetic/other'
        with self.assertRaisesRegex(ValueError,'Event repository mismatch'):self.check()
    def test_base_repository_mismatch_refused(self):
        self.event['pull_request']['base']['repo']['full_name']='synthetic/other'
        with self.assertRaisesRegex(ValueError,'Unreviewed PR base'):self.check()
    def test_head_environment_mismatch_refused(self):
        self.env['GITHUB_HEAD_REF']='synthetic/other'
        with self.assertRaisesRegex(ValueError,'PR environment mismatch'):self.check()
    def test_base_environment_mismatch_refused(self):
        self.env['GITHUB_BASE_REF']='synthetic/other'
        with self.assertRaisesRegex(ValueError,'PR environment mismatch'):self.check()
    def test_absent_or_malformed_metadata_refused(self):
        for event in [None,[],{}, {'pull_request':[]},copy.deepcopy(self.event)]:
            if event==self.event:event['pull_request']['head']['repo']=None
            with self.subTest(event=event),self.assertRaises(ValueError):guard.verify_ci_event(event,self.env,self.sha)

if __name__=='__main__':unittest.main()
