#!/usr/bin/env bash
set -uo pipefail
mkdir -p test-evidence
gradle :app:connectedDebugAndroidTest --no-daemon --stacktrace
test_result=$?
evidence_result=0
for name in 01-bilingual-top 02-modal 03-after-scroll 04-real-translation; do
  adb pull "/sdcard/Pictures/TaoConnect-${name}.png" "test-evidence/${name}.png" || evidence_result=1
done
adb logcat -d -s TaoConnectTest:I > test-evidence/translation.txt
adb logcat -d -s AndroidRuntime:E > test-evidence/android-errors.txt
if [ "$test_result" -ne 0 ]; then
  exit "$test_result"
fi
exit "$evidence_result"
