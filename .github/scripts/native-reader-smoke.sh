#!/usr/bin/env bash
set -u
result=0
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.tachiyomi.ui.reader.viewer.physical.NativePhysicalReaderTest --stacktrace || result=$?
mkdir -p app/build/native-proof
adb shell pm list packages | grep physical || true
adb shell pm list instrumentation || true
adb pull /sdcard/Android/data/app.mihon.physicaldemo.dev/files/native-physical-proof app/build/native-proof || true
adb logcat -d > app/build/native-proof/logcat.txt
find app/build/outputs/androidTest-results -name '*.xml' -type f -print -exec cat {} \; || true
grep -E 'AndroidRuntime|TestRunner|NativePhysicalV40|FATAL|Process: app.mihon|java.lang|eu.kanade' app/build/native-proof/logcat.txt | tail -n 220 || true
if ! find app/build/native-proof -name '02-native-held-page-curl.jpg' | grep -q .; then
  echo 'Native held-curl visual proof was not produced.'
  result=1
fi
exit "$result"
