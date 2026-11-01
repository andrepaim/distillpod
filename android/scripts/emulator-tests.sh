#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${DISTILLPOD_SERIAL:?Set DISTILLPOD_SERIAL to the test emulator, e.g. emulator-5554}"
DISTILLPOD_ADB="${DISTILLPOD_ADB:-adb}"
DISTILLPOD_FIXTURES="${1:?Pass the fixture folder containing ggml-tiny.en.bin and jfk.wav}"
DISTILLPOD_LOG="${DISTILLPOD_LOG:-/tmp/distillpod-instrumentation.log}"
test -f "$DISTILLPOD_FIXTURES/ggml-tiny.en.bin"
test -f "$DISTILLPOD_FIXTURES/jfk.wav"
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest "-PdistillpodFixtureDir=$DISTILLPOD_FIXTURES"
for DISTILLPOD_PACKAGE in app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk; do
    DISTILLPOD_PACKAGE="$(realpath "$DISTILLPOD_PACKAGE")"
    if [[ "$DISTILLPOD_ADB" == *.exe ]]; then DISTILLPOD_PACKAGE="$(wslpath -w "$DISTILLPOD_PACKAGE")"; fi
    "$DISTILLPOD_ADB" -P "${DISTILLPOD_ADB_PORT:-5037}" -s "$DISTILLPOD_SERIAL" install -r "$DISTILLPOD_PACKAGE"
done
"$DISTILLPOD_ADB" -P "${DISTILLPOD_ADB_PORT:-5037}" -s "$DISTILLPOD_SERIAL" shell am instrument -w -r dev.distillpod.android.test/androidx.test.runner.AndroidJUnitRunner | tee "$DISTILLPOD_LOG"
python3 scripts/device_result.py "$DISTILLPOD_LOG"
