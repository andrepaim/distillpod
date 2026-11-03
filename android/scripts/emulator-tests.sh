#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${DISTILLPOD_SERIAL:?Set DISTILLPOD_SERIAL to the test emulator, e.g. emulator-5554}"
DISTILLPOD_ADB="${DISTILLPOD_ADB:-adb}"
DISTILLPOD_FIXTURES="${1:?Pass the fixture folder containing ggml-tiny.en.bin and jfk.wav}"
DISTILLPOD_LOG="${DISTILLPOD_LOG:-/tmp/distillpod-instrumentation.log}"
test -f "$DISTILLPOD_FIXTURES/ggml-tiny.en.bin"
test -f "$DISTILLPOD_FIXTURES/ggml-tiny.bin"
test -f "$DISTILLPOD_FIXTURES/jfk.wav"
DISTILLPOD_USER_STATE="$("$DISTILLPOD_ADB" -P "${DISTILLPOD_ADB_PORT:-5037}" -s "$DISTILLPOD_SERIAL" shell am get-started-user-state 0)"
if [[ "$DISTILLPOD_USER_STATE" != *RUNNING_UNLOCKED* ]]; then
    echo "Unlock the development emulator before running tests; user 0 state: $DISTILLPOD_USER_STATE" >&2
    exit 1
fi
# Keep the compressed regression sample tied to the same pinned upstream checkout.
cp third_party/whisper.cpp/samples/jfk.mp3 "$DISTILLPOD_FIXTURES/jfk.mp3"
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest "-PdistillpodFixtureDir=$DISTILLPOD_FIXTURES"
for DISTILLPOD_PACKAGE in app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk; do
    DISTILLPOD_PACKAGE="$(realpath "$DISTILLPOD_PACKAGE")"
    if [[ "$DISTILLPOD_ADB" == *.exe ]]; then DISTILLPOD_PACKAGE="$(wslpath -w "$DISTILLPOD_PACKAGE")"; fi
    "$DISTILLPOD_ADB" -P "${DISTILLPOD_ADB_PORT:-5037}" -s "$DISTILLPOD_SERIAL" install -r "$DISTILLPOD_PACKAGE"
done
"$DISTILLPOD_ADB" -P "${DISTILLPOD_ADB_PORT:-5037}" -s "$DISTILLPOD_SERIAL" shell am instrument -w -r -e notClass dev.distillpod.android.AuthNetworkDeviceTest,dev.distillpod.android.LiveCaptureDeviceTest,dev.distillpod.android.LiveUnderstandingDeviceTest,dev.distillpod.android.LiveExploreDeviceTest,dev.distillpod.android.PodcastBenchmarkDeviceTest,dev.distillpod.android.FullEpisodeDeviceTest,dev.distillpod.android.PlaybackReliabilityDeviceTest dev.distillpod.android.test/androidx.test.runner.AndroidJUnitRunner | tee "$DISTILLPOD_LOG"
python3 scripts/device_result.py "$DISTILLPOD_LOG"
