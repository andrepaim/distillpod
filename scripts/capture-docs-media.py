#!/usr/bin/env python3
"""Capture synthetic documentation media from an already installed, empty emulator app."""

import argparse
import json
from pathlib import Path
import shutil
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--port", default="5037")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--mode", choices=["screens", "headphones", "distill"], required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    executable = shutil.which(args.adb)
    if not executable:
        raise SystemExit("ADB executable was not found")
    resolved = Path(executable).resolve()
    # Windows ADB launched from WSL needs a Windows filesystem working directory.
    adb_cwd = resolved.parent if resolved.suffix.lower() == ".exe" else None
    adb = [str(resolved), "-P", args.port, "-s", args.serial]

    def run(*command, check=True):
        result = subprocess.run(adb + list(command), cwd=adb_cwd, capture_output=True, timeout=30)
        if check and result.returncode:
            raise RuntimeError("ADB failed: " + result.stderr.decode(errors="replace").strip())
        return result

    run("wait-for-device")
    if run("shell", "getprop", "ro.kernel.qemu").stdout.strip() != b"1":
        raise SystemExit("Refusing to capture from physical hardware")
    args.output.mkdir(parents=True, exist_ok=True)
    remote = "/sdcard/Android/data/dev.distillpod.android/files/documentation"
    run("shell", "rm", "-f", remote + "/ready", remote + "/start", remote + "/done")
    log_path = args.output / (args.mode + "-instrumentation.txt")
    recording = None
    started = None
    with log_path.open("wb") as log:
        process = subprocess.Popen(adb + ["shell", "am", "instrument", "-w", "-r",
            "-e", "class", "dev.distillpod.android.DocumentationMediaDeviceTest",
            "-e", "allowDocumentationCapture", "true", "-e", "documentationMode", args.mode,
            "dev.distillpod.android.test/dev.distillpod.android.IsolatedTestRunner"], cwd=adb_cwd, stdout=log, stderr=log)
        try:
            if args.mode != "screens":
                deadline = time.monotonic() + 60
                while run("shell", "cat", remote + "/ready", check=False).stdout.strip().decode() != args.mode:
                    if process.poll() is not None or time.monotonic() >= deadline:
                        raise RuntimeError("Fixture did not become ready; inspect the local instrumentation receipt")
                    time.sleep(0.5)
                video = "/sdcard/documentation-" + args.mode + ".mp4"
                recording = subprocess.Popen(adb + ["shell", "screenrecord", "--size", "720x1280",
                    "--bit-rate", "2000000", "--time-limit", "60", video],
                    cwd=adb_cwd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                time.sleep(1)
                started = time.monotonic()
                run("shell", "touch", remote + "/start")
                print("Recording " + args.mode, flush=True)
                deadline = time.monotonic() + 55
                while run("shell", "cat", remote + "/done", check=False).stdout.strip().decode() != args.mode:
                    if process.poll() is not None or time.monotonic() >= deadline:
                        raise RuntimeError("Capture did not finish; inspect the local instrumentation receipt")
                    time.sleep(0.5)
                duration = time.monotonic() - started
                # This dedicated documentation emulator has only this recording.
                pids = run("shell", "pidof", "screenrecord", check=False).stdout.decode().split()
                if pids and all(pid.isdigit() for pid in pids):
                    run("shell", "kill", "-2", *pids)
                recording.wait(timeout=15)
                (args.output / (args.mode + "-raw.mp4")).write_bytes(run("exec-out", "cat", video).stdout)
                (args.output / (args.mode + "-timing.json")).write_text(json.dumps({
                    "start_offset_seconds": 1, "duration_seconds": duration,
                    "content": "Synthetic transcript and supplied example AI output; silent recording",
                }, indent=2) + "\n")
                run("shell", "rm", "-f", video)
            process.wait(timeout=90)
            receipt = log_path.read_text(errors="replace")
            if process.returncode or "OK (1 test)" not in receipt or "FAILURES!!!" in receipt:
                raise RuntimeError("Documentation fixture failed; inspect " + str(log_path))
            if args.mode == "screens":
                for name in ["library", "player", "saved", "distill-result", "transcript", "settings", "headphone-settings", "queue"]:
                    data = run("exec-out", "cat", remote + "/" + name + ".png").stdout
                    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
                        raise RuntimeError("Invalid screenshot: " + name)
                    (args.output / (name + ".png")).write_bytes(data)
            print("Documentation capture passed: " + args.mode)
        finally:
            if process.poll() is None:
                process.terminate()
                run("shell", "am", "force-stop", "dev.distillpod.android")
            if recording is not None and recording.poll() is None:
                recording.terminate()


if __name__ == "__main__":
    main()
