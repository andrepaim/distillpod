#!/usr/bin/env bash
set -euo pipefail
# Test APKs must already be installed. This runner never targets an owner phone.
cd "$(dirname "$0")/.."
DISTILLPOD_ADB="${DISTILLPOD_ADB:-adb}"
DISTILLPOD_SERIAL="${DISTILLPOD_SERIAL:?Set DISTILLPOD_SERIAL to a disposable emulator}"
DISTILLPOD_AUTO_LOG="${DISTILLPOD_AUTO_LOG:-/tmp/distillpod-auto-tests}"
auto_adb() { "$DISTILLPOD_ADB" -P "${DISTILLPOD_ADB_PORT:-5037}" -s "$DISTILLPOD_SERIAL" "$@"; }
[[ "$DISTILLPOD_SERIAL" == emulator-* ]] || { echo 'Android Auto fixtures require a disposable emulator' >&2; exit 1; }
[[ "$(auto_adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Target is not an emulator' >&2; exit 1; }
if [[ "${1:-}" != --restart-only ]]; then
    auto_adb shell am instrument -w -r -e class dev.distillpod.android.AutoBrowserDeviceTest \
        dev.distillpod.android.test/dev.distillpod.android.IsolatedTestRunner | tee "$DISTILLPOD_AUTO_LOG-browsers.txt"
    python3 scripts/device_result.py "$DISTILLPOD_AUTO_LOG-browsers.txt"
fi
auto_adb shell am instrument -w -r -e class dev.distillpod.android.AutoRestartDeviceTest#prepareCheckpoint -e autoRestartPhase prepare \
    dev.distillpod.android.test/dev.distillpod.android.IsolatedTestRunner | tee "$DISTILLPOD_AUTO_LOG-before.txt"
python3 scripts/device_result.py "$DISTILLPOD_AUTO_LOG-before.txt"
auto_adb shell am force-stop dev.distillpod.android
auto_adb shell am instrument -w -r -e class dev.distillpod.android.AutoRestartDeviceTest#verifyCheckpoint -e autoRestartPhase verify \
    dev.distillpod.android.test/dev.distillpod.android.IsolatedTestRunner | tee "$DISTILLPOD_AUTO_LOG-after.txt"
python3 scripts/device_result.py "$DISTILLPOD_AUTO_LOG-after.txt"
