#!/usr/bin/env bash
set -uo pipefail
mkdir -p test-evidence
gradle :app:connectedDebugAndroidTest --no-daemon --stacktrace
test_result=$?
adb pull /sdcard/Android/data/com.gaius.taoconnect/files/test-evidence/. test-evidence/ || true
adb logcat -d -s AndroidRuntime:E > test-evidence/android-errors.txt
exit "$test_result"
