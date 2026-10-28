#!/usr/bin/env bash
set -u
result=0
adb logcat -G 16M || true
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.tachiyomi.ui.reader.viewer.physical.NativePhysicalReaderTest --stacktrace || result=$?
mkdir -p app/build/native-proof
adb shell pm list packages | grep physical || true
adb shell pm list instrumentation || true
adb pull /sdcard/Download/native-physical-proof app/build/native-proof || true
adb logcat -d > app/build/native-proof/logcat.txt
find app/build/outputs/androidTest-results -name '*.xml' -type f -print -exec cat {} \; || true
grep -E 'AndroidRuntime|TestRunner|NativePhysical|FATAL|Fatal signal|crash_dump|SIGSEGV|libc|DEBUG|lmkd|java.lang|eu.kanade' app/build/native-proof/logcat.txt | tail -n 500 || true
if ! find app/build/native-proof -type f -name '02-native-held-page-curl.jpg' | grep -q .; then
  echo 'Native held-curl visual proof was not produced.'
  result=1
fi
exit "$result"
