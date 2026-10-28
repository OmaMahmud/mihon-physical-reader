#!/usr/bin/env bash
set -uo pipefail
status=0
gradle connectedDebugAndroidTest --stacktrace || status=$?
adb pull /sdcard/Download/real-reader-proof app/build/proof || { if [ "$status" -eq 0 ]; then status=1; fi; }
for image in reader-single reader-held-curl home-continue library; do
  test -s "app/build/proof/$image.png" || status=1
done
exit "$status"
