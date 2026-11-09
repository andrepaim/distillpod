#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
DISTILLPOD_ADB="${DISTILLPOD_ADB:-adb}"
DISTILLPOD_SERIAL="${DISTILLPOD_SERIAL:?Set DISTILLPOD_SERIAL to a disposable emulator}"
DISTILLPOD_NOTIFICATION_LOG="${DISTILLPOD_NOTIFICATION_LOG:-/tmp/distillpod-notification}"
notification_adb() { "$DISTILLPOD_ADB" -P "${DISTILLPOD_ADB_PORT:-5037}" -s "$DISTILLPOD_SERIAL" "$@"; }
[[ "$DISTILLPOD_SERIAL" == emulator-* ]] || { echo 'Notification fixtures require a disposable emulator' >&2; exit 1; }
[[ "$(notification_adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Target is not an emulator' >&2; exit 1; }
# The emulator runner already installed both APKs. Never target a personal app
# on a physical phone, and never revoke permission inside active instrumentation:
# Android can kill the application process during revocation.
notification_adb shell pm revoke dev.distillpod.android android.permission.POST_NOTIFICATIONS
for notification_phase in recovery verifyRevoked runtimeGrant; do
    if [[ "$notification_phase" == verifyRevoked ]]; then
        notification_adb shell pm revoke dev.distillpod.android android.permission.POST_NOTIFICATIONS
    fi
    notification_adb shell am instrument -w -r -e class dev.distillpod.android.NotificationAccessDeviceTest \
        -e notificationPhase "$notification_phase" \
        dev.distillpod.android.test/dev.distillpod.android.IsolatedTestRunner | tee "$DISTILLPOD_NOTIFICATION_LOG-$notification_phase.log"
    python3 scripts/device_result.py "$DISTILLPOD_NOTIFICATION_LOG-$notification_phase.log"
done
