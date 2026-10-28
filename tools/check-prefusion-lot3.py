"""LOT3 additive laboratory scope; production bytes frozen, historical guard unchanged."""
from pathlib import Path
import subprocess
import tempfile
ROOT=Path(__file__).resolve().parents[1]
BASE='b2b899c8cce73ee0763bf6896fb9ceffe62c2bc6'
ADDED={
'.github/workflows/prefusion-lot3.yml','tools/check-prefusion-lot3.py',
'docs/prefusion/lot3/REPORT.md','docs/prefusion/lot3/BACKEND-DECISION.md',
'labs/prefusion_lot3/matrix.py','tests/prefusion_lot3/test_matrix.py',
'labs/android-lab/build.gradle.kts','labs/android-lab/src/main/AndroidManifest.xml',
'labs/android-lab/src/main/java/fr/jarvis/nexus/prefusion/lab/SyntheticPickerActivity.java',
'labs/android-lab/src/androidTest/java/fr/jarvis/nexus/prefusion/lab/Lot3QualificationTest.java',
'labs/saf-provider/build.gradle.kts','labs/saf-provider/src/main/AndroidManifest.xml',
'labs/saf-provider/src/main/java/fr/jarvis/nexus/prefusion/provider/SyntheticDocumentsProvider.java'}
def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT)
def main():
 subprocess.run(['git','merge-base','--is-ancestor',BASE,'HEAD'],cwd=ROOT,check=True)
 baseline={p:git('cat-file','--filters',BASE+':'+p) for p in git('ls-tree','-r','--name-only',BASE).decode().splitlines()}
 actual=set(git('ls-files','--cached','--others','--exclude-standard').decode().splitlines())
 assert actual-set(baseline)<=ADDED, 'Undeclared addition'
 assert set(baseline)<=actual, 'Deletion'
 for p in actual:
  path=ROOT/p;assert path.is_file() and not path.is_symlink(),p
  if p in baseline:
   if p=='settings.gradle.kts':
    expected=baseline[p]+b'''\n// Explicit synthetic test packages only. No production dependency or default activation.
if (providers.gradleProperty("withPrefusionLab").orNull == "true") {
    check(providers.gradleProperty("withAndroid").orNull == "true")
    include(":nexus-prefusion-provider", ":nexus-prefusion-lab")
    project(":nexus-prefusion-provider").projectDir = file("labs/saf-provider")
    project(":nexus-prefusion-lab").projectDir = file("labs/android-lab")
}
'''
    assert path.read_bytes()==expected,p
   else: assert path.read_bytes()==baseline[p],p
 with tempfile.TemporaryDirectory(prefix='nexus-lot3-historical-') as t:
  subprocess.run(['git','clone','--quiet','--shared','--no-checkout',str(ROOT),t],check=True)
  subprocess.run(['git','checkout','--quiet','--detach',BASE],cwd=t,check=True)
  subprocess.run(['python3','tools/check-prefusion.py'],cwd=t,check=True)
 print('PASS LOT3 scope; production frozen at '+BASE+'; historical guard checked at original scope only')
if __name__=='__main__':main()
