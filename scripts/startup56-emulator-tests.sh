#!/usr/bin/env bash
# Only the owned disposable CI emulator. Physical acceptance is separately coordinated.
set -euo pipefail
cd "$(dirname "$0")/.."
adb_tool="${DISTILLPOD_ADB:-adb}"
serial="${DISTILLPOD_SERIAL:?Set DISTILLPOD_SERIAL to the owned disposable emulator}"
log="${DISTILLPOD_STARTUP_LOG:-/tmp/distillpod-startup56-device.log}"
app=dev.distillpod.android.validation.startup56
test_app="$app.test"
device() { "$adb_tool" -P "${DISTILLPOD_ADB_PORT:-5037}" -s "$serial" "$@"; }
[[ "$serial" == emulator-* ]] || { echo 'Startup fixtures require an owned disposable emulator' >&2; exit 1; }
[[ "$(device shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Target is not an emulator' >&2; exit 1; }
installed="$(device shell pm list packages "$app" | tr -d '\r')"
[[ -z "$installed" ]] || { echo 'Validation package already exists; refusing to replace it' >&2; exit 1; }
flock /tmp/distillpod-dot-full-build.lock ./gradlew --no-daemon -I scripts/emulator-abi.init.gradle --max-workers=2     -I scripts/startup56-validation.init.gradle :app:assembleDebug :app:assembleDebugAndroidTest
aapt="${ANDROID_HOME:?Set ANDROID_HOME}/build-tools/35.0.0/aapt"
main_apk=app/build/outputs/apk/debug/app-debug.apk
test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
[[ "$("$aapt" dump badging "$main_apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")" == "$app" ]]
[[ "$("$aapt" dump badging "$test_apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")" == "$test_app" ]]
[[ "$("$aapt" dump xmltree "$test_apk" AndroidManifest.xml | sed -n 's/.*android:targetPackage[^=]*="\([^"]*\)".*/\1/p')" == "$app" ]]
"$aapt" dump xmltree "$main_apk" AndroidManifest.xml | grep -F 'dev.distillpod.android.DistillPodApplication'
if [[ -n "${DISTILLPOD_EMULATOR_ABI:-}" ]]; then
    python3 scripts/emulator_apk.py app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
fi
for apk in "$main_apk" "$test_apk"; do
    permissions="$("$aapt" dump permissions "$apk")"
    if grep -F 'android.permission.INTERNET' <<< "$permissions"; then
        echo 'Offline validation APK unexpectedly requests INTERNET' >&2; exit 1
    fi
done
main_installed=false
test_installed=false
cleanup() {
    local status=$? cleanup_status=0
    if (( status != 0 )) && $main_installed; then
        # Only this synthetic app's content-free foreground-start warning and stack.
        device logcat -d -t 200 -s TranscriptionService:W '*:S' || true
    fi
    if $test_installed; then device uninstall "$test_app" || cleanup_status=$?; fi
    if $main_installed; then device uninstall "$app" || cleanup_status=$?; fi
    if (( status != 0 )); then return "$status"; fi
    return "$cleanup_status"
}
trap cleanup EXIT
install_apk() {
    local path
    path="$(realpath "$1")"
    if [[ "$adb_tool" == *.exe ]]; then path="$(wslpath -w "$path")"; fi
    device install "$path"
}
install_apk "$main_apk"
main_installed=true
install_apk "$test_apk"
test_installed=true
device shell am instrument -w -r -e class dev.distillpod.android.TranscriptionStartupDeviceTest     "$test_app/dev.distillpod.android.IsolatedTestRunner" | tee "$log"
python3 - "$log" <<'PY'
import sys
from pathlib import Path
sys.path.insert(0, "scripts")
from device_result import verify_result
count = verify_result(Path(sys.argv[1]).read_text())
if count != 4:
    raise SystemExit(f"Expected all four startup recovery cases, got {count}")
print("Startup recovery acceptance passed: four cases")
PY
