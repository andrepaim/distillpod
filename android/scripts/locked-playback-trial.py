#!/usr/bin/env python3
"""Run the explicitly staged debug fixture outside instrumentation for a real hour."""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)

    def shell(*command, input_text=None):
        for attempt in range(3):
            try:
                return subprocess.run([args.adb, "-P", "5037", "-s", args.serial, "shell", *command],
                                      input=input_text, check=True, capture_output=True, text=True, timeout=45).stdout.strip()
            except subprocess.CalledProcessError as error:
                # Retry transport setup only, never an action that may already have run.
                if attempt == 2 or not any(s in error.stderr for s in ("cannot connect to daemon", "device offline", "not found")):
                    raise
                time.sleep(1)

    settings_file = args.output / "device-settings.json"
    original = json.loads(settings_file.read_text()) if settings_file.exists() else {
        "stayAwake": shell("settings", "get", "global", "stay_on_while_plugged_in"),
        "volume": int(re.search(r"volume is (\d+)", shell("cmd", "media_session", "volume", "--getfine")).group(1)),
    }
    # Contains device settings only; keep this recoverable if the host is interrupted.
    settings_file.write_text(json.dumps(original, indent=2))
    summary = {"status": "failed", "instrumentationDuringHour": False,
               "usbConnected": True, "muted": True, "bluetooth": "No hardware available"}
    try:
        shell("cmd", "media_session", "volume", "--setfine", "0")
        shell("settings", "put", "global", "stay_on_while_plugged_in", "0")
        shell("am", "start", "-W", "-n", "dev.distillpod.android/.MainActivity")
        # Target this session directly, after asynchronous service restoration.
        # Global media dispatch may choose no session before first playback.
        for _ in range(15):
            sessions = shell("cmd", "media_session", "list-sessions")
            if "package=dev.distillpod.android" in sessions:
                break
            time.sleep(1)
        else:
            raise RuntimeError("DistillPod media session did not become available")
        result = shell("cmd", "media_session", "monitor", "androidx.media3.session.id.", input_text="play\nq\n")
        if "No session found" in result:
            raise RuntimeError("Could not address the DistillPod media session")
        shell("input", "keyevent", "223")
        for attempt in range(15):
            try:
                existing = shell("run-as", "dev.distillpod.android", "cat", "files/validation/playback-health.jsonl")
                break
            except subprocess.CalledProcessError as error:
                if attempt == 14 or "No such file" not in error.stderr:
                    raise
                time.sleep(1)
        prior = [json.loads(line) for line in existing.splitlines() if line.strip()]
        armed_after = prior[-1]["elapsedMs"] if prior else -1
        start = None
        deadline = time.monotonic() + 64 * 60
        last_elapsed = None
        baseline = None
        while time.monotonic() < deadline:
            time.sleep(30)
            try:
                raw = shell("run-as", "dev.distillpod.android", "cat", "files/validation/playback-health.jsonl")
            except (subprocess.SubprocessError, OSError) as error:
                print(f"ADB read pending: {type(error).__name__}", flush=True)
                continue
            records = [json.loads(line) for line in raw.splitlines() if line.strip()]
            (args.output / "samples.jsonl").write_text(raw + "\n")
            if not records:
                continue
            if last_elapsed == records[-1]["elapsedMs"]:
                raise RuntimeError("Playback diagnostic samples stopped advancing")
            last_elapsed = records[-1]["elapsedMs"]
            if start is None:
                eligible = [r for r in records if r["elapsedMs"] > armed_after and r["playing"] and not r["interactive"] and r["locked"]]
                if not eligible:
                    print("Waiting for actual locked, playing sample", flush=True)
                    continue
                start = eligible[0]
                baseline = start["positionMs"] - start["elapsedMs"]
            trial = [r for r in records if r["elapsedMs"] >= start["elapsedMs"]]
            for previous, current in zip(trial, trial[1:]):
                if current["elapsedMs"] - previous["elapsedMs"] > 15_000:
                    raise RuntimeError("Playback sample gap exceeded 15 seconds")
            for record in trial:
                if not record["playing"] or record["interactive"] or not record["locked"]:
                    raise RuntimeError("Locked playback was interrupted")
                if record["errors"] or record["underruns"] > start["underruns"]:
                    raise RuntimeError("Player error or audio underrun during locked interval")
                if record["buffering"] != start["buffering"] or record["speed"] != 1:
                    raise RuntimeError("Playback buffered or speed changed during locked interval")
                if abs(record["positionMs"] - record["elapsedMs"] - baseline) > 2000:
                    raise RuntimeError("Actual playback position drift exceeded two seconds")
            elapsed = trial[-1]["elapsedMs"] - start["elapsedMs"]
            print(f"Locked playback: {elapsed / 60_000:.1f}/60 minutes; errors={trial[-1]['errors']}; underruns={trial[-1]['underruns']}; thermal={trial[-1]['thermal']}", flush=True)
            if elapsed >= 3_600_000:
                summary.update(lockedPlayback="passed", lockedElapsedMs=elapsed, positionAdvanceMs=trial[-1]["positionMs"] - start["positionMs"],
                               sampleCount=len(trial), errors=trial[-1]["errors"], additionalUnderruns=trial[-1]["underruns"] - start["underruns"],
                               maxThermalStatus=max(r["thermal"] for r in trial), charging=any(r["charging"] for r in trial))
                break
        else:
            raise RuntimeError("No complete 60-minute locked interval before safety deadline")
        # These short instrumentation phases start only after the real hour ends.
        def phase(name, method):
            result = shell("am", "instrument", "-w", "-r", "-e", "lockedTrial", name, "-e", "class",
                           "dev.distillpod.android.PlaybackReliabilityDeviceTest#" + method,
                           "dev.distillpod.android.test/androidx.test.runner.AndroidJUnitRunner")
            (args.output / (name + ".txt")).write_text(result + "\n")
            if "OK (1 test)" not in result:
                raise RuntimeError(name + " device check did not pass")

        shell("input", "keyevent", "224")
        phase("controls", "focusAndPersistFixture")
        shell("am", "force-stop", "dev.distillpod.android")
        shell("am", "start", "-W", "-n", "dev.distillpod.android/.MainActivity")
        phase("reopen", "verifyFixtureAfterProcessDeath")
        summary["focusAndProcessRecovery"] = "passed"
        summary["status"] = "passed"
    except BaseException as error:
        summary["error"] = f"{type(error).__name__}: {error}"
        raise
    finally:
        (args.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
        failures = []
        for action in [
            ("am", "instrument", "-w", "-r", "-e", "lockedTrial", "restore", "-e", "class",
             "dev.distillpod.android.PlaybackReliabilityDeviceTest#restoreLockedTrial", "dev.distillpod.android.test/androidx.test.runner.AndroidJUnitRunner"),
            ("settings", "put", "global", "stay_on_while_plugged_in", original["stayAwake"]),
            ("cmd", "media_session", "volume", "--setfine", str(original["volume"])),
        ]:
            try:
                result = shell(*action)
                if action[0] == "am":
                    (args.output / "restore.txt").write_text(result + "\n")
                    if "OK (1 test)" not in result:
                        raise RuntimeError("Queue restoration instrumentation did not pass")
            except BaseException as error:
                failures.append(str(error))
        if failures:
            summary["status"] = "failed"
            summary["cleanupErrors"] = failures
            (args.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
            raise RuntimeError("; ".join(failures))


if __name__ == "__main__":
    main()
