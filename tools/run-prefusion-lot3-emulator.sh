#!/usr/bin/env bash
# Clean CI emulator only; fixed synthetic packages, no personal device selector.
set -Eeuo pipefail
lot3_phase=START
lot3_finish() {
  lot3_result=$?
  trap - EXIT
  set +e
  mkdir -p labs/android-lab/build/reports/prefusion-lot3
  python3 - "$lot3_phase" "$lot3_result" <<'PY' | tee labs/android-lab/build/reports/prefusion-lot3/summary.txt
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
phase,original=sys.argv[1],int(sys.argv[2])
failed=False
for module,expected in [('nexus-android-store',9),('labs/android-lab',5)]:
    files=list(Path(module,'build/outputs/androidTest-results').rglob('TEST-*.xml'))
    if not files:
        print(f'::notice title=NEX-PF-02 test evidence::{module}: no JUnit XML available; NOT_RUN or UNKNOWN, never assumed PASS')
        if original==0: failed=True
        continue
    try:
        if len(files)>64 or any(p.stat().st_size>2097152 for p in files): raise ValueError('report limit')
        roots=[ET.parse(p).getroot() for p in files]
        counts={k:sum(int(r.get(k,'0')) for r in roots) for k in ['tests','failures','errors','skipped']}
        print(f'::notice title=NEX-PF-02 test evidence::{module}: '+', '.join(f'{k}={v}' for k,v in counts.items()))
        if original==0 and (counts['tests']!=expected or any(counts[k] for k in ['failures','errors','skipped'])): failed=True
    except (ValueError,OSError,ET.ParseError):
        print(f'::error title=NEX-PF-02 evidence invalid::{module}: invalid or excessive JUnit XML; no test success inferred')
        failed=True
if original:
    print(f'::error title=NEX-PF-02 failed phase::{phase}; original exit={original}; inspect authenticated CI logs and XML artifacts')
elif failed:
    print(f'::error title=NEX-PF-02 evidence missing::{phase}; successful command without complete expected test evidence is refused')
else:
    print('::notice title=NEX-PF-02 evidence::9 existing +5 laboratory cases executed; platform SQL support remains separate from pass count')
sys.exit(2 if failed else 0)
PY
  lot3_summary_result=$?
  adb logcat -d -s NEXUS_LOT3:I > lot3-platform-log.txt 2>/dev/null
  if [ "$lot3_result" -eq 0 ] && [ "$lot3_summary_result" -ne 0 ]; then lot3_result=$lot3_summary_result; fi
  exit "$lot3_result"
}
trap lot3_finish EXIT
lot3_phase=BUILD_SYNTHETIC_PROVIDER
bash gradlew -PwithAndroid=true -PwithPrefusionLab=true :nexus-prefusion-provider:assembleDebug --no-daemon --max-workers=2
lot3_phase=INSTALL_SYNTHETIC_PROVIDER
adb install -t labs/saf-provider/build/outputs/apk/debug/nexus-prefusion-provider-debug.apk
lot3_phase=RUN_PLATFORM_TESTS
bash gradlew -PwithAndroid=true -PwithPrefusionLab=true :nexus-android-store:connectedDebugAndroidTest :nexus-prefusion-lab:connectedDebugAndroidTest --no-daemon --max-workers=2
lot3_phase=COMPLETE
