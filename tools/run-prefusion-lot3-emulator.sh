#!/usr/bin/env bash
# Clean CI emulator only; fixed synthetic packages, no personal device selector.
set -Eeuo pipefail
lot3_phase=START
lot3_finish() {
  lot3_result=$?
  trap - EXIT
  set +e
  mkdir -p labs/android-lab/build/reports/prefusion-lot3
  python3 tools/prefusion-lot3-evidence.py "$lot3_phase" "$lot3_result" | tee labs/android-lab/build/reports/prefusion-lot3/summary.txt
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
bash gradlew -PwithAndroid=true -PwithPrefusionLab=true :nexus-android-store:connectedDebugAndroidTest :nexus-prefusion-lab:connectedDebugAndroidTest --continue --no-daemon --max-workers=2
lot3_phase=COMPLETE
