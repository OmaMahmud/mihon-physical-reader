#!/usr/bin/env bash
set -euo pipefail
previous=$(find previous -name '*.apk' -type f | head -n1)
adb install "$previous"
adb shell run-as app.mihon.physicalreader.dev mkdir -p files
printf '%s' 'native-reader-update-data-retained' > update-marker.txt
adb push update-marker.txt /data/local/tmp/native-update-marker.txt
adb shell run-as app.mihon.physicalreader.dev cp /data/local/tmp/native-update-marker.txt files/native-update-proof
adb install -r output/Mihon-Physical-v4.0.1.apk
marker=$(adb shell run-as app.mihon.physicalreader.dev cat files/native-update-proof | tr -d '\r')
test "$marker" = 'native-reader-update-data-retained'
adb shell dumpsys package app.mihon.physicalreader.dev > output/update-verification.txt
grep -q 'versionCode=42' output/update-verification.txt
printf '\nUPDATE_INSTALL_SUCCEEDED_DATA_RETAINED\n' | tee -a output/update-verification.txt
