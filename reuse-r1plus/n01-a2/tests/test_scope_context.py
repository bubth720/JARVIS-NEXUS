"""Offline regression of guard context. No legacy guard or runtime is modified."""
import hashlib
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[3]
REVIEWED='b7d9d884ddd034e3580a2268fce69ca43030e8c5'
M53='eae61fb62a9ccd4073b96d71454f89e5a943eae9'
NEX07='8c778be1b302d10c8dcbf8444f6e6e051477c825'
ENV=dict(os.environ,PYTHONDONTWRITEBYTECODE='1')

def run(root,*args):
    return subprocess.run(args,cwd=root,env=ENV,capture_output=True,text=True,timeout=60)

class ScopeContextTest(unittest.TestCase):
    def clone(self,ref=REVIEWED):
        temp=tempfile.TemporaryDirectory(prefix='n01-a2-scope-')
        self.addCleanup(temp.cleanup)
        p=Path(temp.name)/'source'
        r=run(ROOT,'git','clone','--shared','--quiet','--no-checkout',str(ROOT),str(p))
        self.assertEqual(0,r.returncode,r.stderr)
        r=run(p,'git','checkout','--quiet','--detach',ref)
        self.assertEqual(0,r.returncode,r.stderr)
        return p

    def guard(self,root):
        return run(root,sys.executable,'reuse-r1plus/check.py')

    def test_actual_legacy_context_refuses_undeclared_reuse_files(self):
        r=run(ROOT,sys.executable,'tools/check-prefusion.py')
        self.assertNotEqual(0,r.returncode)
        self.assertIn('ValueError: Undeclared files:',r.stderr)
        self.assertIn('reuse-r1plus/adapter/ReadOnlyConsultationAdapter.kt',r.stderr)

    def test_actual_reuse_guard_preserves_all_m53_files(self):
        r=self.guard(ROOT)
        self.assertEqual(0,r.returncode,r.stderr)
        self.assertIn('"historical_files_preserved": 329',r.stdout)

    def test_all_historical_guard_bytes_and_modes_unchanged(self):
        paths=run(ROOT,'git','ls-tree','-r','--name-only',REVIEWED).stdout.splitlines()
        guards=[p for p in paths if p.endswith('.py') and (Path(p).name.startswith('check') or Path(p).name=='test_scope.py')]
        self.assertGreater(len(guards),5)
        for name in guards:
            original=subprocess.check_output(['git','show',REVIEWED+':'+name],cwd=ROOT)
            self.assertEqual(hashlib.sha256(original).digest(),hashlib.sha256((ROOT/name).read_bytes()).digest(),name)
            mode=run(ROOT,'git','ls-tree',REVIEWED,'--',name).stdout.split()[0]
            self.assertEqual(mode=='100755',bool((ROOT/name).stat().st_mode&0o111),name)

    def test_mutated_documentary_contract_is_refused(self):
        p=self.clone();f=p/'nexus-core/src/main/kotlin/fr/jarvis/nexus/rag/RagTypes.kt'
        f.write_bytes(f.read_bytes()+b'\n// synthetic unauthorized mutation\n')
        self.assertNotEqual(0,self.guard(p).returncode)

    def test_mutated_frozen_workflow_is_refused(self):
        p=self.clone();f=p/'.github/workflows/nex-07.yml'
        f.write_bytes(f.read_bytes()+b'\n# proposed routing requires explicit frozen-scope approval\n')
        r=self.guard(p)
        self.assertNotEqual(0,r.returncode)
        self.assertIn('.github/workflows/nex-07.yml',r.stderr)

    def test_executable_mode_change_is_refused(self):
        p=self.clone();f=p/'nexus-core/src/main/kotlin/fr/jarvis/nexus/rag/RagTypes.kt'
        f.chmod(f.stat().st_mode|0o111)
        self.assertNotEqual(0,self.guard(p).returncode)

    def test_symlink_replacement_is_refused(self):
        p=self.clone();f=p/'nexus-core/src/main/kotlin/fr/jarvis/nexus/rag/RagTypes.kt'
        original=f.read_bytes();other=p/'build/source.txt';other.parent.mkdir(exist_ok=True);other.write_bytes(original)
        f.unlink();f.symlink_to(other)
        self.assertNotEqual(0,self.guard(p).returncode)

    def test_addition_outside_reviewed_laboratory_is_refused(self):
        p=self.clone();(p/'UNDECLARED.txt').write_text('synthetic fixture')
        self.assertNotEqual(0,self.guard(p).returncode)

    def test_text_addition_inside_reviewed_laboratory_is_permitted(self):
        p=self.clone();f=p/'reuse-r1plus/n01-a2/synthetic.txt';f.parent.mkdir();f.write_text('synthetic fixture')
        self.assertEqual(0,self.guard(p).returncode)

    def test_historical_nex07_still_passes_only_its_original_context(self):
        p=self.clone(NEX07);r=run(p,sys.executable,'tools/check-nex07.py')
        self.assertEqual(0,r.returncode,r.stderr)

    def test_historical_m53_still_passes_its_original_context(self):
        p=self.clone(M53);r=run(p,sys.executable,'m53review/check.py')
        self.assertEqual(0,r.returncode,r.stderr)

if __name__=='__main__':unittest.main()
