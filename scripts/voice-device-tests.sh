#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
DISTILLPOD_ADB="${DISTILLPOD_ADB:-adb}"
DISTILLPOD_SERIAL="${DISTILLPOD_SERIAL:?Set DISTILLPOD_SERIAL to a disposable emulator}"
DISTILLPOD_VOICE_LOG="${DISTILLPOD_VOICE_LOG:-/tmp/distillpod-voice-device.log}"
voice_adb() { "$DISTILLPOD_ADB" -P "${DISTILLPOD_ADB_PORT:-5037}" -s "$DISTILLPOD_SERIAL" "$@"; }
[[ "$DISTILLPOD_SERIAL" == emulator-* ]] || { echo 'Voice fixtures require a disposable emulator' >&2; exit 1; }
[[ "$(voice_adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Target is not an emulator' >&2; exit 1; }
voice_build_args=(-PdistillpodVoiceEnabled=true)
if [[ $# -gt 0 ]]; then voice_build_args+=("-PdistillpodFixtureDir=$(realpath "$1")"); fi
./gradlew --no-daemon -I scripts/emulator-abi.init.gradle :app:assembleDebug :app:assembleDebugAndroidTest "${voice_build_args[@]}"
if [[ -n "${DISTILLPOD_EMULATOR_ABI:-}" ]]; then
    python3 scripts/emulator_apk.py app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
fi
for voice_apk in app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk; do
    voice_apk="$(realpath "$voice_apk")"
    if [[ "$DISTILLPOD_ADB" == *.exe ]]; then voice_apk="$(wslpath -w "$voice_apk")"; fi
    voice_adb install -r "$voice_apk"
done
voice_adb shell am instrument -w -r -e class dev.distillpod.android.HeadphoneVoiceDeviceTest,dev.distillpod.android.VoiceApiKeyDeviceTest \
    dev.distillpod.android.test/dev.distillpod.android.IsolatedTestRunner | tee "$DISTILLPOD_VOICE_LOG"
python3 scripts/device_result.py "$DISTILLPOD_VOICE_LOG"
