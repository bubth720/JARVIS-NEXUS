"""Fail-closed NEX-07 delta guard; does not retest historical FUSION-02 receipt.

The FUSION-02 receipt fixes SHA256 for *its own historical source tree*. The
new code is intentionally not misrepresented by that old evidence.
"""
from __future__ import annotations
import subprocess
from pathlib import Path

BASE='06ccf98a7709364bf09cc1ecea1c35663d5787cf'
EXPECTED={
    '.github/workflows/nex-07.yml',
    'docs/nex-07/REPORT.md',
    'nexus-android-store/src/main/kotlin/fr/jarvis/nexus/android/SelectedDocumentImporter.kt',
    'nexus-android-store/src/test/kotlin/fr/jarvis/nexus/android/SelectedDocumentImporterTest.kt',
    'tools/check-nex07.py',
}

def run(*args): return subprocess.run(args, check=True, capture_output=True,text=True).stdout.strip()

def check():
    run('git','merge-base','--is-ancestor',BASE,'HEAD')
    actual=set(run('git','diff','--name-only',BASE,'HEAD').splitlines())
    if actual != EXPECTED:
        raise ValueError(f'Unexpected scope: missing={sorted(EXPECTED-actual)}, extra={sorted(actual-EXPECTED)}')
    # Existing v4 contracts, migration, FUSION02 receipt and all evidence must be byte-identical.
    for path in ('docs/fusion-02','contracts','nexus-core','nexus-jvm-store','nexus-services-adapter'):
        if run('git','diff','--name-only',BASE,'HEAD','--',path):
            raise ValueError('Protected owner modified: '+path)
    source=Path('nexus-android-store/src/main/kotlin/fr/jarvis/nexus/android/SelectedDocumentImporter.kt').read_text()
    if 'if (source.byteCount > 2L * 1024 * 1024)' not in source:
        raise ValueError('Required preflight missing')
    if 'return CatalogResult.Rejected(KnowledgeError.LIMIT_EXCEEDED)' not in source:
        raise ValueError('Missing fail-closed quota result')
    print('NEX-07 scope and original FUSION-02 evidence protected; no Android GO')

if __name__=='__main__':check()
