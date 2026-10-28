"""Bounded public diagnostics of synthetic laboratory JUnit evidence only."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def summarize(folder, expected):
    files = sorted(folder.rglob('TEST-*.xml'))
    result = dict(reports=0, tests=0, failures=0, errors=0, skipped=0, details=[])
    if len(files) > 64 or any(p.stat().st_size > 2097152 for p in files):
        raise ValueError('report limit')
    for file in files:
        root = ET.parse(file).getroot()
        counts = {k: int(root.get(k, '0')) for k in ('tests', 'failures', 'errors', 'skipped')}
        if root.tag != 'testsuite' or any(v < 0 for v in counts.values()):
            raise ValueError('invalid suite counts')
        result['reports'] += 1
        for k, value in counts.items():
            result[k] += value
        for case in root.findall('testcase'):
            name = case.get('classname', '')
            if not name.startswith(('fr.jarvis.nexus.prefusion.lab.', 'fr.jarvis.nexus.android.')):
                continue
            for problem in list(case.findall('failure')) + list(case.findall('error')):
                # No stack trace, logcat, source document, URI grant or environment dump.
                message = problem.get('message') or problem.text or problem.tag
                first = message.splitlines()[0] if message.splitlines() else problem.tag
                first = ''.join(c if c.isprintable() else ' ' for c in first)[:240]
                result['details'].append(name[:120] + '.' + case.get('name', '')[:120] + ': ' + first)
    result['details'] = result['details'][:12]
    result['complete'] = (result['reports'] > 0 and result['tests'] == expected
                          and not any(result[k] for k in ('failures', 'errors', 'skipped')))
    return result


def annotation(level, title, message):
    safe = message.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print(f'::{level} title={title}::{safe}')


def main(phase, original):
    failed = False
    for module, expected in [('nexus-android-store', 9), ('labs/android-lab', 5)]:
        try:
            result = summarize(Path(module, 'build/outputs/androidTest-results'), expected)
            counts = ', '.join(f'{k}={result[k]}' for k in ('reports', 'tests', 'failures', 'errors', 'skipped'))
            annotation('notice', 'NEX-PF-02 test evidence', module + ': ' + counts)
            for detail in result['details']:
                annotation('warning', 'NEX-PF-02 synthetic test failure', detail)
            if original == 0 and not result['complete']:
                failed = True
        except (ValueError, OSError, ET.ParseError):
            annotation('error', 'NEX-PF-02 evidence invalid', module + ': invalid or excessive public XML')
            failed = True
    if original:
        annotation('error', 'NEX-PF-02 failed phase', phase + '; original exit=' + str(original))
    elif failed:
        annotation('error', 'NEX-PF-02 evidence missing', phase + '; complete expected test evidence required')
    return 2 if failed else 0


if __name__ == '__main__':
    raise SystemExit(main(sys.argv[1], int(sys.argv[2])))
