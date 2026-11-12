"""NEXUS-00 successor guard: historical guards in their own context, exact delta on HEAD.

Why: nex-07.yml runs on every pull_request and its default lane executes the
PRE-FUSION lot1 guard on HEAD. That guard (unchanged here) freezes the lot1
tree, so every legitimate successor (LOT3, M50-S1, M53, REUSE R1+) fails it with
"Undeclared files" although none of them touches the NEX-07 quota code.

This guard never edits or relaxes a historical guard. It:
  1. executes each historical guard unchanged in a detached worktree of that
     exact commit (where it is defined to pass);
  2. freezes every guard file and every other blob and mode of BASE (REUSE R1+ b7d9d88) on HEAD, allowing
     only the declared workflow lane change and the declared NEXUS-00 additions.
No quota, source, migration, contract or Android code is modified by NEXUS-00.
"""
from pathlib import Path
import json
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
BASE = 'b7d9d884ddd034e3580a2268fce69ca43030e8c5'
WORKFLOW = '.github/workflows/nex-07.yml'
# (guard command, commit where that guard is defined to pass)
HISTORICAL = [
    (['python3', 'tools/check-prefusion.py'], '201f0d2b03209de104157595a557143fe87eb94e'),
    (['python3', 'tools/check-prefusion.py'], 'b2b899c8cce73ee0763bf6896fb9ceffe62c2bc6'),
    (['python3', 'tools/check-prefusion-lot3.py'], 'c5dee7ab0344a23bfd80b13cd52d09e8b26241dd'),
    (['python3', 'tools/check-m50-s1.py'], 'be70faa41d202d77eb8cf0cc5c831e10cfb020b5'),
    (['python3', 'm53review/check.py'], 'eae61fb62a9ccd4073b96d71454f89e5a943eae9'),
    (['python3', 'reuse-r1plus/check.py'], BASE),
]
ADDITIONS = {
    'tools/check-nexus00-scope.py',
    'tests/nexus00/test_scope.py',
    'ci-guard-scope/nexus-ci-diagnosis.md',
    'nexus-android-store/src/test/kotlin/fr/jarvis/nexus/android/QuotaPreflightBoundaryTest.kt',
}
OLD_LANE = '            python3 tools/check-prefusion.py\n'
NEW_LANE = ('            python3 tools/check-nexus00-scope.py\n'
            '            python3 -m unittest discover -s tests/nexus00 -v\n')


def git(*args, cwd=ROOT):
    return subprocess.check_output(['git', *args], cwd=cwd)


def expected_workflow(original: bytes) -> bytes:
    text = original.decode()
    # Default lane appears once in each job (jvm, android-host).
    if text.count(OLD_LANE) != 2:
        raise ValueError('Unexpected historical nex-07 default lane')
    return text.replace(OLD_LANE, NEW_LANE).encode()


def tree(commit):
    out = {}
    for entry in git('ls-tree', '-rz', commit).split(b'\0'):
        if not entry:
            continue
        meta, name = entry.split(b'\t')
        mode, kind, oid = meta.decode().split()
        if kind != 'blob':
            raise ValueError('Non-blob entry in frozen tree: ' + name.decode())
        out[name.decode()] = (mode, oid)
    return out


def verify_delta(root, frozen, actual, additions, replacements, hash_of):
    """Pure check, unit-tested: no deletion, no mode/byte drift, exact additions."""
    missing = set(frozen) - set(actual)
    if missing:
        raise ValueError('Historical file deleted: ' + ', '.join(sorted(missing)))
    extra = set(actual) - set(frozen)
    undeclared = extra - set(additions)
    if undeclared:
        raise ValueError('Undeclared files: ' + ', '.join(sorted(undeclared)))
    for name, (mode, oid) in frozen.items():
        path = root / name
        if path.is_symlink() or not path.is_file():
            raise ValueError('Missing/non-regular file: ' + name)
        if bool(path.stat().st_mode & 0o111) != (mode == '100755'):
            raise ValueError('Historical mode changed: ' + name)
        if name in replacements:
            if path.read_bytes() != replacements[name]:
                raise ValueError('Declared replacement differs: ' + name)
        elif hash_of(name) != oid:
            raise ValueError('Historical bytes changed: ' + name)
    for name in extra:
        path = root / name
        if path.is_symlink() or not path.is_file() or path.stat().st_mode & 0o111:
            raise ValueError('Addition must be a regular non-executable file: ' + name)


def run_historical():
    results = []
    for command, commit in HISTORICAL:
        script = command[1]
        # The guard runs from its own commit; HEAD copies are covered by the BASE freeze.
        with tempfile.TemporaryDirectory(prefix='nexus00-') as temp:
            work = Path(temp) / 'tree'
            git('worktree', 'add', '--quiet', '--detach', str(work), commit)
            try:
                subprocess.run(command, cwd=work, check=True, stdout=subprocess.DEVNULL)
            finally:
                git('worktree', 'remove', '--force', str(work))
        results.append({'guard': script, 'commit': commit, 'result': 'PASS'})
    return results


def main():
    subprocess.run(['git', 'merge-base', '--is-ancestor', BASE, 'HEAD'], cwd=ROOT, check=True)
    historical = run_historical()
    frozen = tree(BASE)
    actual = git('ls-files', '--cached', '--others', '--exclude-standard').decode().splitlines()
    replacement = expected_workflow(git('show', BASE + ':' + WORKFLOW))
    verify_delta(ROOT, frozen, actual, ADDITIONS, {WORKFLOW: replacement},
                 lambda name: git('hash-object', '--', name).decode().strip())
    print(json.dumps({'status': 'PASS', 'base': BASE, 'historicalGuards': historical,
                      'historicalFilesPreserved': len(frozen), 'additions': sorted(set(actual) - set(frozen)),
                      'quotaCodeChanged': False, 'android': 'NOT_RUN by this guard'}, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, subprocess.CalledProcessError) as error:
        print('FAIL NEXUS-00 scope: ' + str(error), file=sys.stderr)
        sys.exit(1)
