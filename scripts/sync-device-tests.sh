#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${DISTILLPOD_SERIAL:?Set the isolated Android emulator serial}"
[[ "$DISTILLPOD_SERIAL" == emulator-* ]] || { echo 'Sync fixtures require an emulator' >&2; exit 1; }
DISTILLPOD_ADB="${DISTILLPOD_ADB:-adb}"
DISTILLPOD_LOG="${DISTILLPOD_SYNC_LOG:-/tmp/distillpod-sync-device.log}"
DISTILLPOD_FLAGS=( -P "${DISTILLPOD_ADB_PORT:-5037}" -s "$DISTILLPOD_SERIAL" )
DISTILLPOD_QEMU="$("$DISTILLPOD_ADB" "${DISTILLPOD_FLAGS[@]}" shell getprop ro.kernel.qemu)"
[[ "${DISTILLPOD_QEMU//$'\r'/}" == 1 ]] || { echo 'Target is not a verified emulator' >&2; exit 1; }
for artifact in app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk; do
    artifact="$(realpath "$artifact")"
    if [[ "$DISTILLPOD_ADB" == *.exe ]]; then artifact="$(wslpath -w "$artifact")"; fi
    "$DISTILLPOD_ADB" "${DISTILLPOD_FLAGS[@]}" install -r "$artifact"
done
"$DISTILLPOD_ADB" "${DISTILLPOD_FLAGS[@]}" shell am instrument -w -r -e syncEmulators true \
    -e syncEmulatorHost 10.0.2.2 \
    -e class dev.distillpod.android.FullContentSyncDeviceTest,dev.distillpod.android.SubscriptionSyncDeviceTest,dev.distillpod.android.DatabaseUpgradeDeviceTest,dev.distillpod.android.SubscriptionSyncUiDeviceTest,dev.distillpod.android.PublicFeedReviewDeviceTest \
    dev.distillpod.android.test/dev.distillpod.android.IsolatedTestRunner | tee "$DISTILLPOD_LOG"
python3 scripts/device_result.py "$DISTILLPOD_LOG"
for phase in seed resume; do
    "$DISTILLPOD_ADB" "${DISTILLPOD_FLAGS[@]}" shell am instrument -w -r -e syncEmulators true \
        -e syncRestartPhase "$phase" -e class dev.distillpod.android.SubscriptionRestartDeviceTest \
        dev.distillpod.android.test/dev.distillpod.android.IsolatedTestRunner | tee "$DISTILLPOD_LOG.$phase"
    python3 scripts/device_result.py "$DISTILLPOD_LOG.$phase"
    if [[ "$phase" == seed ]]; then
        "$DISTILLPOD_ADB" "${DISTILLPOD_FLAGS[@]}" shell am force-stop dev.distillpod.android
    fi
done
# Restore a normal fresh process for any subsequent manual emulator use.
"$DISTILLPOD_ADB" "${DISTILLPOD_FLAGS[@]}" shell am force-stop dev.distillpod.android
