"""Black-box the actual shell bootstrap without an Android device or SDK."""
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


@unittest.skipUnless(os.name == "posix" and shutil.which("bash"), "Unix shell fixture")
class EmulatorBootstrapTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        for name in ["scripts", "bin", "fixtures", "third_party/whisper.cpp/samples"]:
            (self.root / name).mkdir(parents=True, exist_ok=True)
        source = Path(__file__).resolve().parent
        for name in ["emulator-tests.sh", "android-auto-tests.sh", "voice-device-tests.sh", "notification-device-tests.sh", "device_result.py"]:
            shutil.copy2(source / name, self.root / "scripts" / name)
        for name in ["ggml-tiny.en.bin", "ggml-tiny.bin", "jfk.wav"]:
            (self.root / "fixtures" / name).touch()
        (self.root / "third_party/whisper.cpp/samples/jfk.mp3").touch()
        self.executable("gradlew", """#!/bin/sh
printf '%s\\n' "$@" > gradle-args
mkdir -p app/build/outputs/apk/debug app/build/outputs/apk/androidTest/debug
touch app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
""")
        self.executable("bin/sleep", "#!/bin/sh\nexit 0\n")
        self.executable("bin/fake-adb", """#!/usr/bin/env python3
import sys,os,json,pathlib
root=pathlib.Path(os.environ['PROOF_ROOT']);cmd=sys.argv[5:]
with (root/'calls').open('a') as f:f.write(json.dumps(cmd)+'\\n')
if cmd[:3] in [['shell','getprop','ro.boot.qemu'],['shell','getprop','ro.kernel.qemu']]:print(os.environ.get('PROOF_QEMU','1'))
elif cmd[:3]==['shell','am','get-started-user-state']:
 p=root/'count';n=int(p.read_text()) if p.exists() else 0;p.write_text(str(n+1))
 print('RUNNING_UNLOCKED' if os.environ['PROOF_CASE']=='race' and n>=2 else 'RUNNING_LOCKED')
elif cmd[:3]==['shell','am','instrument']:
 phase=cmd[cmd.index('notificationPhase')+1] if 'notificationPhase' in cmd else ''
 if 'class' in cmd and cmd[cmd.index('class')+1]==os.environ.get('PROOF_FAIL_CLASS'):
  print('INSTRUMENTATION_STATUS: stack=synthetic queue shutdown failure\\nINSTRUMENTATION_STATUS_CODE: -2\\nFAILURES!!!')
 elif phase and phase==os.environ.get('PROOF_FAIL_PHASE'):
  print('INSTRUMENTATION_STATUS: stream=phase='+phase+' step=runtime-permission-granted\\nINSTRUMENTATION_STATUS_CODE: -2\\nFAILURES!!!')
 else:print('INSTRUMENTATION_STATUS_CODE: 0\\nOK (1 test)\\nINSTRUMENTATION_CODE: -1')
""")

    def executable(self, name, text):
        path = self.root / name
        path.write_text(text)
        path.chmod(0o700)

    def run_fixture(self, case, ci="true", serial="emulator-5554", fail_class=""):
        env = os.environ.copy()
        env.update(GITHUB_ACTIONS=ci, DISTILLPOD_ADB=str(self.root / "bin/fake-adb"),
                   DISTILLPOD_SERIAL=serial, DISTILLPOD_LOG=str(self.root / "instrumentation.log"),
                   DISTILLPOD_AUTO_LOG=str(self.root / "auto-log"), PROOF_ROOT=str(self.root), PROOF_CASE=case, PROOF_FAIL_CLASS=fail_class,
                   PATH=str(self.root / "bin") + ":" + env["PATH"])
        result = subprocess.run(["bash", str(self.root / "scripts/emulator-tests.sh"), str(self.root / "fixtures")],
                                cwd=self.root, env=env, capture_output=True, text=True, timeout=15)
        file = self.root / "calls"
        calls = [json.loads(line) for line in file.read_text().splitlines()] if file.exists() else []
        return result, calls

    def test_waits_for_storage_and_cleans_browser_fixture(self):
        result, calls = self.run_fixture("race")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("3", (self.root / "count").read_text())
        self.assertIn("-I\nscripts/emulator-abi.init.gradle\n", (self.root / "gradle-args").read_text())
        self.assertEqual(2, sum(call[0] == "install" for call in calls))
        command = next(call for call in calls if call[:3] == ["shell", "am", "instrument"])
        self.assertIn("#realDirectoryShowsPublisherThumbnails", " ".join(command))
        self.assertIn("AutoRestartDeviceTest", " ".join(command))
        self.assertIn("LiveMomentQualityDeviceTest", " ".join(command))
        excluded = command[command.index("notClass") + 1].split(",")
        self.assertIn("dev.distillpod.android.DocumentationMediaDeviceTest", excluded)
        self.assertIn("dev.distillpod.android.HeadphoneVoiceDeviceTest", excluded)
        self.assertIn("dev.distillpod.android.YouTubeAcceptanceDeviceTest", excluded)
        self.assertIn("dev.distillpod.android.OwnerVideocastDeviceTest", excluded)
        # Opt-in measurement; an assumption skip would fail the ordinary result check.
        self.assertIn("dev.distillpod.android.LibrarySearchBenchmarkDeviceTest", excluded)
        phases=[call for call in calls if call[:3]==["shell","am","instrument"]]
        self.assertEqual(8,len(phases))
        self.assertIn("dev.distillpod.android.CloudQueueTeardownDeviceTest",excluded)
        self.assertIn("dev.distillpod.android.CloudQueueChoicePhoneDeviceTest",excluded)
        self.assertEqual("dev.distillpod.android.CloudQueueTeardownDeviceTest",phases[1][phases[1].index("class")+1])
        self.assertIn("AutoBrowserDeviceTest"," ".join(phases[2]))
        self.assertIn("prepareCheckpoint"," ".join(phases[3]))
        self.assertIn("verifyCheckpoint"," ".join(phases[4]))
        self.assertTrue(any(call==["shell","am","force-stop","dev.distillpod.android"] for call in calls))
        self.assertTrue(any(call[:3] == ["shell", "am", "clear-debug-app"] for call in calls))

    def test_queue_shutdown_failure_retains_log_and_stops_later_phases(self):
        result,calls=self.run_fixture("race",fail_class="dev.distillpod.android.CloudQueueTeardownDeviceTest")
        self.assertNotEqual(0,result.returncode)
        phases=[call for call in calls if call[:3]==["shell","am","instrument"]]
        self.assertEqual(2,len(phases))
        log=(self.root/"instrumentation-queue-shutdown.log").read_text()
        self.assertIn("synthetic queue shutdown failure",log)
        self.assertIn("FAILURES!!!",log)
        self.assertTrue(any(call[:3]==["shell","am","clear-debug-app"] for call in calls))
        self.assertFalse(any("AutoBrowserDeviceTest" in " ".join(call) and "notClass" not in call for call in calls))

    def test_storage_timeout_is_bounded_and_never_installs(self):
        result, calls = self.run_fixture("stuck")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("30", (self.root / "count").read_text())
        self.assertFalse(any(call[0] == "install" for call in calls))

    def test_locked_owner_phone_is_rejected_without_wait_or_install(self):
        result, calls = self.run_fixture("phone", ci="false", serial="OWNER_PHONE")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("1", (self.root / "count").read_text())
        self.assertFalse(any(call[0] == "install" for call in calls))

    def test_ci_setup_rejects_a_physical_device_before_any_adb_action(self):
        result, calls = self.run_fixture("ci-phone", serial="OWNER_PHONE")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], calls)

    def voice_fixture(self, serial="emulator-5554", qemu="1"):
        env = os.environ.copy()
        env.update(DISTILLPOD_ADB=str(self.root / "bin/fake-adb"), DISTILLPOD_SERIAL=serial,
                   DISTILLPOD_VOICE_LOG=str(self.root / "voice.log"), PROOF_ROOT=str(self.root),
                   PROOF_CASE="voice", PROOF_QEMU=qemu)
        result = subprocess.run(["bash", str(self.root / "scripts/voice-device-tests.sh"), str(self.root / "fixtures")],
                                cwd=self.root, env=env, capture_output=True, text=True, timeout=15)
        file = self.root / "calls"
        calls = [json.loads(line) for line in file.read_text().splitlines()] if file.exists() else []
        return result, calls

    def test_voice_runner_enables_preview_and_selects_only_synthetic_tests(self):
        result, calls = self.voice_fixture()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("-PdistillpodVoiceEnabled=true", (self.root / "gradle-args").read_text())
        self.assertIn("-I\nscripts/emulator-abi.init.gradle\n", (self.root / "gradle-args").read_text())
        self.assertEqual(2, sum(call[0] == "install" for call in calls))
        commands = [call for call in calls if call[:3] == ["shell", "am", "instrument"]]
        self.assertEqual(1, len(commands))
        self.assertEqual("dev.distillpod.android.HeadphoneVoiceDeviceTest,dev.distillpod.android.VoiceApiKeyDeviceTest",
                         commands[0][commands[0].index("class") + 1])

    def test_voice_runner_refuses_phone_before_any_adb_action(self):
        result, calls = self.voice_fixture(serial="OWNER_PHONE")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], calls)
        self.assertFalse((self.root / "gradle-args").exists())

    def test_voice_runner_refuses_non_emulator_before_build_or_install(self):
        result, calls = self.voice_fixture(qemu="0")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([["shell", "getprop", "ro.kernel.qemu"]], calls)
        self.assertFalse((self.root / "gradle-args").exists())

    def notification_fixture(self, serial="emulator-5554", qemu="1", fail_phase=""):
        env = os.environ.copy()
        env.update(DISTILLPOD_ADB=str(self.root / "bin/fake-adb"), DISTILLPOD_SERIAL=serial,
                   DISTILLPOD_NOTIFICATION_LOG=str(self.root / "notification"), PROOF_ROOT=str(self.root),
                   PROOF_CASE="notification", PROOF_QEMU=qemu, PROOF_FAIL_PHASE=fail_phase)
        result = subprocess.run(["bash", str(self.root / "scripts/notification-device-tests.sh")],
                                cwd=self.root, env=env, capture_output=True, text=True, timeout=15)
        file = self.root / "calls"
        calls = [json.loads(line) for line in file.read_text().splitlines()] if file.exists() else []
        return result, calls

    def test_notification_runner_runs_three_phases_with_revocation_between_processes(self):
        result, calls = self.notification_fixture()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        phases = [call for call in calls if call[:3] == ["shell", "am", "instrument"]]
        self.assertEqual(3, len(phases))
        for command, phase in zip(phases, ["recovery", "verifyRevoked", "runtimeGrant"]):
            self.assertEqual("dev.distillpod.android.NotificationAccessDeviceTest", command[command.index("class") + 1])
            self.assertEqual(phase, command[command.index("notificationPhase") + 1])
        revokes = [i for i, call in enumerate(calls) if call[:3] == ["shell", "pm", "revoke"]]
        self.assertEqual(2, len(revokes))
        self.assertLess(revokes[0], calls.index(phases[0]))
        self.assertLess(calls.index(phases[0]), revokes[1])
        self.assertLess(revokes[1], calls.index(phases[1]))

    def test_notification_runner_refuses_phone_before_any_adb_action(self):
        result, calls = self.notification_fixture(serial="OWNER_PHONE")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], calls)

    def test_notification_runner_refuses_spoofed_emulator_before_permission_changes(self):
        result, calls = self.notification_fixture(qemu="0")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([["shell", "getprop", "ro.kernel.qemu"]], calls)

    def test_notification_failure_preserves_phase_log_and_stops_before_next_phase(self):
        result, calls = self.notification_fixture(fail_phase="verifyRevoked")
        self.assertNotEqual(0, result.returncode)
        phases = [call[call.index("notificationPhase") + 1] for call in calls
                  if call[:3] == ["shell", "am", "instrument"]]
        self.assertEqual(["recovery", "verifyRevoked"], phases)
        log = (self.root / "notification-verifyRevoked.log").read_text()
        self.assertIn("phase=verifyRevoked step=runtime-permission-granted", log)
        self.assertIn("FAILURES!!!", log)
        self.assertFalse((self.root / "notification-runtimeGrant.log").exists())

    def test_ci_retains_all_phase_logs_on_failure(self):
        workflow = Path(__file__).resolve().parent.parent / ".github/workflows/ci.yml"
        artifact = workflow.read_text().split("name: emulator-test-results", 1)[1]
        self.assertIn("/tmp/distillpod-notification-*.log", artifact)
        self.assertIn("/tmp/distillpod-instrumentation*.log", artifact)
