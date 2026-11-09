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
if [[ "${GITHUB_ACTIONS:-false}" == true ]]; then
    [[ "$DISTILLPOD_SERIAL" == emulator-* ]] || { echo 'CI setup requires a disposable emulator' >&2; exit 1; }
    DISTILLPOD_QEMU="$("$DISTILLPOD_ADB" -P "${DISTILLPOD_ADB_PORT:-5037}" -s "$DISTILLPOD_SERIAL" shell getprop ro.boot.qemu)"
    if [[ "${DISTILLPOD_QEMU//$'\r'/}" != 1 ]]; then
        DISTILLPOD_QEMU="$("$DISTILLPOD_ADB" -P "${DISTILLPOD_ADB_PORT:-5037}" -s "$DISTILLPOD_SERIAL" shell getprop ro.kernel.qemu)"
    fi
    [[ "${DISTILLPOD_QEMU//$'\r'/}" == 1 ]] || { echo 'CI setup requires a verified emulator' >&2; exit 1; }
    # sys.boot_completed can precede credential-encrypted storage readiness.
    # Wait for the disposable emulator's normal boot; never unlock an owner phone.
    for DISTILLPOD_ATTEMPT in {1..30}; do
        DISTILLPOD_USER_STATE="$("$DISTILLPOD_ADB" -P "${DISTILLPOD_ADB_PORT:-5037}" -s "$DISTILLPOD_SERIAL" shell am get-started-user-state 0)"
        [[ "$DISTILLPOD_USER_STATE" == *RUNNING_UNLOCKED* ]] && break
        sleep 1
    done
else
    DISTILLPOD_USER_STATE="$("$DISTILLPOD_ADB" -P "${DISTILLPOD_ADB_PORT:-5037}" -s "$DISTILLPOD_SERIAL" shell am get-started-user-state 0)"
fi
if [[ "$DISTILLPOD_USER_STATE" != *RUNNING_UNLOCKED* ]]; then
    echo "Unlock the development emulator before running tests; user 0 state: $DISTILLPOD_USER_STATE" >&2
    exit 1
fi
# A fresh CI image has not completed Chrome's first-run experience. Configure
# Chromium's documented test command line on the disposable emulator only, so
# ACTION_VIEW reaches the real callback page instead of an onboarding screen.
DISTILLPOD_BROWSER_CONFIGURED=false
cleanup_browser_fixture() {
    if [[ "$DISTILLPOD_BROWSER_CONFIGURED" == true ]]; then
        "$DISTILLPOD_ADB" -P "${DISTILLPOD_ADB_PORT:-5037}" -s "$DISTILLPOD_SERIAL" shell am clear-debug-app || true
        "$DISTILLPOD_ADB" -P "${DISTILLPOD_ADB_PORT:-5037}" -s "$DISTILLPOD_SERIAL" shell rm -f /data/local/tmp/chrome-command-line || true
    fi
}
trap cleanup_browser_fixture EXIT
if [[ "${GITHUB_ACTIONS:-false}" == true ]]; then
    DISTILLPOD_FLAGS="$(mktemp)"
    printf '%s\n' '_ --disable-fre --no-first-run --no-default-browser-check' > "$DISTILLPOD_FLAGS"
    "$DISTILLPOD_ADB" -P "${DISTILLPOD_ADB_PORT:-5037}" -s "$DISTILLPOD_SERIAL" push "$DISTILLPOD_FLAGS" /data/local/tmp/chrome-command-line
    rm -f "$DISTILLPOD_FLAGS"
    DISTILLPOD_BROWSER_CONFIGURED=true
    "$DISTILLPOD_ADB" -P "${DISTILLPOD_ADB_PORT:-5037}" -s "$DISTILLPOD_SERIAL" shell am set-debug-app --persistent com.android.chrome
    "$DISTILLPOD_ADB" -P "${DISTILLPOD_ADB_PORT:-5037}" -s "$DISTILLPOD_SERIAL" shell am force-stop com.android.chrome
fi
# Keep the compressed regression sample tied to the same pinned upstream checkout.
cp third_party/whisper.cpp/samples/jfk.mp3 "$DISTILLPOD_FIXTURES/jfk.mp3"
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest "-PdistillpodFixtureDir=$DISTILLPOD_FIXTURES"
for DISTILLPOD_PACKAGE in app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk; do
    DISTILLPOD_PACKAGE="$(realpath "$DISTILLPOD_PACKAGE")"
    if [[ "$DISTILLPOD_ADB" == *.exe ]]; then DISTILLPOD_PACKAGE="$(wslpath -w "$DISTILLPOD_PACKAGE")"; fi
    "$DISTILLPOD_ADB" -P "${DISTILLPOD_ADB_PORT:-5037}" -s "$DISTILLPOD_SERIAL" install -r "$DISTILLPOD_PACKAGE"
done
"$DISTILLPOD_ADB" -P "${DISTILLPOD_ADB_PORT:-5037}" -s "$DISTILLPOD_SERIAL" shell am instrument -w -r -e notClass dev.distillpod.android.NotificationAccessDeviceTest,dev.distillpod.android.QueueControlsPhoneDeviceTest,dev.distillpod.android.OwnerVideocastDeviceTest,dev.distillpod.android.YouTubeAcceptanceDeviceTest,dev.distillpod.android.VideoStartupDeviceTest,dev.distillpod.android.HeadphoneVoiceDeviceTest,dev.distillpod.android.DocumentationMediaDeviceTest,dev.distillpod.android.AutoBrowserDeviceTest,dev.distillpod.android.AutoRestartDeviceTest,dev.distillpod.android.FullContentSyncDeviceTest,dev.distillpod.android.SubscriptionSyncDeviceTest,dev.distillpod.android.SubscriptionRestartDeviceTest,dev.distillpod.android.AuthNetworkDeviceTest,dev.distillpod.android.LiveCaptureDeviceTest,dev.distillpod.android.LiveMomentQualityDeviceTest,dev.distillpod.android.LiveUnderstandingDeviceTest,dev.distillpod.android.LiveExploreDeviceTest,dev.distillpod.android.SocialCapabilityDeviceTest,dev.distillpod.android.SocialOutputDeviceTest,dev.distillpod.android.PodcastBenchmarkDeviceTest,dev.distillpod.android.FullEpisodeDeviceTest,dev.distillpod.android.PlaybackReliabilityDeviceTest,dev.distillpod.android.SearchDesignDeviceTest#realDirectoryShowsPublisherThumbnails dev.distillpod.android.test/dev.distillpod.android.IsolatedTestRunner | tee "$DISTILLPOD_LOG"
python3 scripts/device_result.py "$DISTILLPOD_LOG"

scripts/android-auto-tests.sh

scripts/notification-device-tests.sh
