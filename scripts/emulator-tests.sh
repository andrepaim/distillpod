#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${DISTILLPOD_SERIAL:?Set DISTILLPOD_SERIAL to the test emulator, e.g. emulator-5554}"
DISTILLPOD_ADB="${DISTILLPOD_ADB:-adb}"
DISTILLPOD_FIXTURES="${1:?Pass the fixture folder containing ggml-tiny.en.bin and jfk.wav}"
DISTILLPOD_LOG="${DISTILLPOD_LOG:-/tmp/distillpod-instrumentation.log}"
DISTILLPOD_APP=dev.distillpod.android
DISTILLPOD_TEST_APP="$DISTILLPOD_APP.test"
DISTILLPOD_TEST_RUNNER="$DISTILLPOD_TEST_APP/$DISTILLPOD_APP.IsolatedTestRunner"
DISTILLPOD_ONBOARDING_CLASS="$DISTILLPOD_APP.OnboardingNavigationDeviceTest"
DISTILLPOD_ONBOARDING_LIBRARY_CLASS="$DISTILLPOD_APP.OnboardingLibraryDeviceTest"

device() { "$DISTILLPOD_ADB" -P "${DISTILLPOD_ADB_PORT:-5037}" -s "$DISTILLPOD_SERIAL" "$@"; }
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
DISTILLPOD_INSTALL_FLAGS=(-r)
if [[ "${GITHUB_ACTIONS:-false}" == true ]]; then
    DISTILLPOD_INSTALLED_PACKAGES="$(device shell pm list packages "$DISTILLPOD_APP")"
    if grep -Fxq -e "package:$DISTILLPOD_APP" -e "package:$DISTILLPOD_TEST_APP" <<< "${DISTILLPOD_INSTALLED_PACKAGES//$'\r'/}"; then
        echo 'Onboarding validation requires a fresh emulator. A DistillPod package is already installed.' >&2
        exit 1
    fi
    DISTILLPOD_INSTALL_FLAGS=()
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
./gradlew --no-daemon -I scripts/emulator-abi.init.gradle :app:assembleDebug :app:assembleDebugAndroidTest "-PdistillpodFixtureDir=$DISTILLPOD_FIXTURES"
if [[ -n "${DISTILLPOD_EMULATOR_ABI:-}" ]]; then
    python3 scripts/emulator_apk.py app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
fi
DISTILLPOD_APP_APK=app/build/outputs/apk/debug/app-debug.apk
install_apk() {
    local apk
    apk="$(realpath "$1")"
    shift
    if [[ "$DISTILLPOD_ADB" == *.exe ]]; then apk="$(wslpath -w "$apk")"; fi
    device install "$@" "$apk"
}
install_apk "$DISTILLPOD_APP_APK" "${DISTILLPOD_INSTALL_FLAGS[@]}"
install_apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk "${DISTILLPOD_INSTALL_FLAGS[@]}"

if [[ "${GITHUB_ACTIONS:-false}" == true ]]; then
    run_onboarding_case() {
        local selector="$1" phase="$2" log
        shift 2
        log="${DISTILLPOD_LOG%.log}-onboarding-$phase.log"
        device shell am instrument -w -r -e firstRunOnboarding true "$@" \
            -e class "$selector" "$DISTILLPOD_TEST_RUNNER" | tee "$log"
        python3 scripts/device_result.py "$log" 1
    }

    clear_onboarding_state() {
        local result
        if ! result="$(device shell pm clear "$1")" || [[ "${result//$'\r'/}" != Success ]]; then
            echo "Could not clear onboarding test app data for $1" >&2
            return 1
        fi
    }

    # Clearing user data between fresh cases preserves the first-install timestamp.
    for selector in \
        "$DISTILLPOD_ONBOARDING_CLASS#externalDestinationsAndRecoverySurviveActivityRecreation" \
        "$DISTILLPOD_ONBOARDING_CLASS#mediaSearchPlaysAbovePendingSetup" \
        "$DISTILLPOD_ONBOARDING_CLASS#welcomeSkipCompletesSetup" \
        "$DISTILLPOD_ONBOARDING_CLASS#recoverySkipCompletesSetup" \
        "$DISTILLPOD_ONBOARDING_CLASS#recoveryOpenLibraryCompletesSetup" \
        "$DISTILLPOD_ONBOARDING_LIBRARY_CLASS#signedOutListenerBrowsesPlaysAndFindsBothSetupActions" \
        "$DISTILLPOD_ONBOARDING_LIBRARY_CLASS#saveActionUsesLibraryOrSearchAndDismissalLeavesGuideAvailable"; do
        run_onboarding_case "$selector" "${selector#*#}"
        clear_onboarding_state "$DISTILLPOD_APP"
    done

    # Replace the package over a seeded library to exercise Android's update metadata
    # and in-place data retention. Keep the fixture provider and its grants until verified.
    run_onboarding_case "$DISTILLPOD_ONBOARDING_CLASS#seedLibraryBeforeUpgrade" upgrade-seed -e firstRunUpgradeSeed true
    install_apk "$DISTILLPOD_APP_APK" -r
    run_onboarding_case "$DISTILLPOD_ONBOARDING_CLASS#updatedInstallOpensItsLibraryWithoutTheNewSetup" upgrade-populated \
        -e firstRunUpgrade true -e firstRunUpgradeSeed true
    clear_onboarding_state "$DISTILLPOD_APP"
    clear_onboarding_state "$DISTILLPOD_TEST_APP"
    run_onboarding_case "$DISTILLPOD_ONBOARDING_CLASS#updatedInstallOpensItsLibraryWithoutTheNewSetup" upgrade-empty -e firstRunUpgrade true
    clear_onboarding_state "$DISTILLPOD_APP"
fi
"$DISTILLPOD_ADB" -P "${DISTILLPOD_ADB_PORT:-5037}" -s "$DISTILLPOD_SERIAL" shell am instrument -w -r -e notClass "dev.distillpod.android.CloudQueueChoicePhoneDeviceTest,dev.distillpod.android.CloudQueueTeardownDeviceTest,dev.distillpod.android.NotificationAccessDeviceTest,dev.distillpod.android.NotificationPromptDeviceTest,dev.distillpod.android.QueueControlsPhoneDeviceTest,dev.distillpod.android.OwnerVideocastDeviceTest,dev.distillpod.android.YouTubeAcceptanceDeviceTest,dev.distillpod.android.VideoStartupDeviceTest,dev.distillpod.android.HeadphoneVoiceDeviceTest,dev.distillpod.android.DocumentationMediaDeviceTest,dev.distillpod.android.VisualBaselineDeviceTest,dev.distillpod.android.AutoBrowserDeviceTest,dev.distillpod.android.AutoRestartDeviceTest,dev.distillpod.android.FullContentSyncDeviceTest,dev.distillpod.android.SubscriptionSyncDeviceTest,dev.distillpod.android.SyncCompactionDeviceTest,dev.distillpod.android.SubscriptionRestartDeviceTest,dev.distillpod.android.AuthNetworkDeviceTest,dev.distillpod.android.LiveCaptureDeviceTest,dev.distillpod.android.LiveMomentQualityDeviceTest,dev.distillpod.android.LiveUnderstandingDeviceTest,dev.distillpod.android.LiveExploreDeviceTest,dev.distillpod.android.PodcastBenchmarkDeviceTest,dev.distillpod.android.LibrarySearchBenchmarkDeviceTest,dev.distillpod.android.FullEpisodeDeviceTest,dev.distillpod.android.PlaybackReliabilityDeviceTest,dev.distillpod.android.SearchDesignDeviceTest#realDirectoryShowsPublisherThumbnails,$DISTILLPOD_ONBOARDING_CLASS,$DISTILLPOD_ONBOARDING_LIBRARY_CLASS" "$DISTILLPOD_TEST_RUNNER" | tee "$DISTILLPOD_LOG"
python3 scripts/device_result.py "$DISTILLPOD_LOG"

# Closing this controller deliberately ends its service connection. Use a separate
# instrumentation process so subsequent cases receive a fresh application graph.
DISTILLPOD_QUEUE_SHUTDOWN_LOG="${DISTILLPOD_LOG%.log}-queue-shutdown.log"
"$DISTILLPOD_ADB" -P "${DISTILLPOD_ADB_PORT:-5037}" -s "$DISTILLPOD_SERIAL" shell am instrument -w -r -e class dev.distillpod.android.CloudQueueTeardownDeviceTest dev.distillpod.android.test/dev.distillpod.android.IsolatedTestRunner | tee "$DISTILLPOD_QUEUE_SHUTDOWN_LOG"
python3 scripts/device_result.py "$DISTILLPOD_QUEUE_SHUTDOWN_LOG"

scripts/android-auto-tests.sh

scripts/notification-device-tests.sh
