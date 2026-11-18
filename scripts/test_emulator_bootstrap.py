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
    onboarding_class = "dev.distillpod.android.OnboardingNavigationDeviceTest"
    onboarding_library_class = "dev.distillpod.android.OnboardingLibraryDeviceTest"
    fresh_methods = (
        "externalDestinationsAndRecoverySurviveActivityRecreation",
        "mediaSearchPlaysAbovePendingSetup",
        "welcomeSkipCompletesSetup",
        "recoverySkipCompletesSetup",
        "recoveryOpenLibraryCompletesSetup",
    )
    fresh_library_methods = (
        "signedOutListenerBrowsesPlaysAndFindsBothSetupActions",
        "saveActionUsesLibraryOrSearchAndDismissalLeavesGuideAvailable",
    )

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
 print('RUNNING_UNLOCKED' if os.environ['PROOF_CASE']=='ready' or os.environ['PROOF_CASE']=='race' and n>=2 else 'RUNNING_LOCKED')
elif cmd[:3]==['shell','pm','list']:print(os.environ.get('PROOF_INSTALLED_PACKAGES',''))
elif cmd[:3]==['shell','pm','clear']:print(os.environ.get('PROOF_CLEAR_RESULT','Success'))
elif cmd[:3]==['shell','am','instrument']:
 phase=cmd[cmd.index('notificationPhase')+1] if 'notificationPhase' in cmd else ''
 if 'class' in cmd and cmd[cmd.index('class')+1]==os.environ.get('PROOF_FAIL_CLASS'):
  print('INSTRUMENTATION_STATUS: stack=synthetic queue shutdown failure\\nINSTRUMENTATION_STATUS_CODE: -2\\nFAILURES!!!')
 elif phase and phase==os.environ.get('PROOF_FAIL_PHASE'):
  print('INSTRUMENTATION_STATUS: stream=phase='+phase+' step=runtime-permission-granted\\nINSTRUMENTATION_STATUS_CODE: -2\\nFAILURES!!!')
 elif 'firstRunOnboarding' in cmd and os.environ.get('PROOF_ONBOARDING_COUNT')=='2':
  print('INSTRUMENTATION_STATUS_CODE: 0\\nOK (2 tests)\\nINSTRUMENTATION_CODE: -1')
 else:print('INSTRUMENTATION_STATUS_CODE: 0\\nOK (1 test)\\nINSTRUMENTATION_CODE: -1')
""")

    def executable(self, name, text):
        path = self.root / name
        path.write_text(text)
        path.chmod(0o700)

    def run_fixture(self, case, ci="true", serial="emulator-5554", fail_class="", installed="",
                    qemu="1", clear_result="Success", onboarding_count="1"):
        env = os.environ.copy()
        env.update(GITHUB_ACTIONS=ci, DISTILLPOD_ADB=str(self.root / "bin/fake-adb"),
                   DISTILLPOD_SERIAL=serial, DISTILLPOD_LOG=str(self.root / "instrumentation.log"),
                   DISTILLPOD_AUTO_LOG=str(self.root / "auto-log"), PROOF_ROOT=str(self.root),
                   PROOF_CASE=case, PROOF_FAIL_CLASS=fail_class, PROOF_QEMU=qemu,
                   PROOF_INSTALLED_PACKAGES=installed, PROOF_CLEAR_RESULT=clear_result,
                   PROOF_ONBOARDING_COUNT=onboarding_count, PATH=str(self.root / "bin") + ":" + env["PATH"])
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
        self.assertEqual(3, sum(call[0] == "install" for call in calls))
        command = next(call for call in calls if call[:3] == ["shell", "am", "instrument"] and "notClass" in call)
        self.assertIn("#realDirectoryShowsPublisherThumbnails", " ".join(command))
        self.assertIn("AutoRestartDeviceTest", " ".join(command))
        self.assertIn("LiveMomentQualityDeviceTest", " ".join(command))
        excluded = command[command.index("notClass") + 1].split(",")
        self.assertIn("dev.distillpod.android.DocumentationMediaDeviceTest", excluded)
        # Opt-in screenshot capture for dependency upgrades; skipped runs would fail the result check.
        self.assertIn("dev.distillpod.android.VisualBaselineDeviceTest", excluded)
        self.assertIn("dev.distillpod.android.HeadphoneVoiceDeviceTest", excluded)
        self.assertIn("dev.distillpod.android.YouTubeAcceptanceDeviceTest", excluded)
        self.assertIn("dev.distillpod.android.OwnerVideocastDeviceTest", excluded)
        # Opt-in measurement; an assumption skip would fail the ordinary result check.
        self.assertIn("dev.distillpod.android.LibrarySearchBenchmarkDeviceTest", excluded)
        # Cloud-emulator acceptance runs in scripts/sync-device-tests.sh only.
        self.assertIn("dev.distillpod.android.SyncCompactionDeviceTest", excluded)
        phases = [call for call in calls if call[:3] == ["shell", "am", "instrument"] and "firstRunOnboarding" not in call]
        self.assertEqual(10, len(phases))
        self.assertIn(self.onboarding_class, excluded)
        self.assertIn(self.onboarding_library_class, excluded)
        self.assertIn("dev.distillpod.android.NotificationPromptDeviceTest",excluded)
        self.assertIn("dev.distillpod.android.CloudQueueTeardownDeviceTest",excluded)
        self.assertIn("dev.distillpod.android.CloudQueueChoicePhoneDeviceTest",excluded)
        self.assertEqual("dev.distillpod.android.CloudQueueTeardownDeviceTest",phases[1][phases[1].index("class")+1])
        self.assertIn("AutoBrowserDeviceTest"," ".join(phases[2]))
        self.assertIn("prepareCheckpoint"," ".join(phases[3]))
        self.assertIn("verifyCheckpoint"," ".join(phases[4]))
        self.assertTrue(any(call==["shell","am","force-stop","dev.distillpod.android"] for call in calls))
        self.assertTrue(any(call[:3] == ["shell", "am", "clear-debug-app"] for call in calls))

    def test_hosted_onboarding_is_isolated_and_exercises_fresh_and_updated_installs(self):
        result, calls = self.run_fixture("race")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(["shell", "pm", "list", "packages", "dev.distillpod.android"], calls)
        phases = [call for call in calls if call[:3] == ["shell", "am", "instrument"] and "firstRunOnboarding" in call]
        upgrade = "updatedInstallOpensItsLibraryWithoutTheNewSetup"
        fresh_cases = [self.onboarding_class + "#" + method for method in self.fresh_methods]
        fresh_cases += [self.onboarding_library_class + "#" + method for method in self.fresh_library_methods]
        update_cases = [self.onboarding_class + "#" + method for method in ["seedLibraryBeforeUpgrade", upgrade, upgrade]]
        self.assertEqual(fresh_cases + update_cases, [phase[phase.index("class") + 1] for phase in phases])
        for phase in phases:
            self.assertEqual("true", phase[phase.index("firstRunOnboarding") + 1])
        for phase in phases[:len(fresh_cases)]:
            self.assertNotIn("firstRunUpgrade", phase)
            self.assertNotIn("firstRunUpgradeSeed", phase)

        installs = [index for index, call in enumerate(calls) if call[0] == "install"]
        self.assertLess(installs[1], calls.index(phases[0]))
        self.assertEqual(calls.index(phases[-3]) + 1, installs[2])
        self.assertNotIn("-r", calls[installs[0]])
        self.assertNotIn("-r", calls[installs[1]])
        self.assertEqual(["install", "-r", calls[installs[0]][-1]], calls[installs[2]])
        self.assertIn("firstRunUpgradeSeed", phases[-3])
        self.assertNotIn("firstRunUpgrade", phases[-3])
        self.assertIn("firstRunUpgradeSeed", phases[-2])
        self.assertIn("firstRunUpgrade", phases[-2])
        self.assertIn("firstRunUpgrade", phases[-1])
        self.assertNotIn("firstRunUpgradeSeed", phases[-1])

        clear = ["shell", "pm", "clear", "dev.distillpod.android"]
        clear_provider = ["shell", "pm", "clear", "dev.distillpod.android.test"]
        self.assertEqual(len(fresh_cases) + 2, calls.count(clear))
        for phase in phases[:len(fresh_cases)] + phases[-2:]:
            self.assertEqual(clear, calls[calls.index(phase) + 1])
        self.assertIn("notClass", calls[calls.index(phases[-1]) + 2])
        self.assertEqual(clear_provider, calls[calls.index(phases[-1]) - 1])
        self.assertEqual(len(phases), len(list(self.root.glob("instrumentation-onboarding-*.log"))))

    def test_hosted_onboarding_refuses_to_replace_an_existing_app(self):
        result, calls = self.run_fixture("race", installed="package:dev.distillpod.android")
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("already installed", result.stderr)
        self.assertFalse(any(call[0] == "install" or call[:3] == ["shell", "pm", "clear"] for call in calls))

    def test_unverified_emulator_cannot_run_the_destructive_setup(self):
        result, calls = self.run_fixture("race", qemu="0")
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual([["shell", "getprop", "ro.boot.qemu"], ["shell", "getprop", "ro.kernel.qemu"]], calls)

    def test_ordinary_local_run_excludes_onboarding_without_resetting_app_data(self):
        result, calls = self.run_fixture("ready", ci="false")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse(any("firstRunOnboarding" in call or call[:3] == ["shell", "pm", "clear"] for call in calls))
        self.assertTrue(all("-r" in call for call in calls if call[0] == "install"))
        ordinary = next(call for call in calls if "notClass" in call)
        excluded = ordinary[ordinary.index("notClass") + 1].split(",")
        self.assertIn(self.onboarding_class, excluded)
        self.assertIn(self.onboarding_library_class, excluded)

    def test_unexpected_onboarding_test_count_stops_before_ordinary(self):
        result, calls = self.run_fixture("race", onboarding_count="2")
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Expected 1 test", result.stderr)
        self.assertEqual(1, sum(call[:3] == ["shell", "am", "instrument"] for call in calls))
        self.assertEqual(1, len(list(self.root.glob("instrumentation-onboarding-*.log"))))
        self.assertFalse(any(call[:3] == ["shell", "pm", "clear"] for call in calls))

    def test_failed_onboarding_reset_stops_before_running_the_next_case(self):
        result, calls = self.run_fixture("race", clear_result="Failed")
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Could not clear onboarding test app data", result.stderr)
        self.assertEqual(1, sum(call[:3] == ["shell", "am", "instrument"] for call in calls))

    def test_queue_shutdown_failure_retains_log_and_stops_later_phases(self):
        result,calls=self.run_fixture("race",fail_class="dev.distillpod.android.CloudQueueTeardownDeviceTest")
        self.assertNotEqual(0,result.returncode)
        phases = [call for call in calls if call[:3] == ["shell", "am", "instrument"] and "firstRunOnboarding" not in call]
        self.assertEqual(2, len(phases))
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

    def test_notification_runner_runs_five_phases_with_revocation_between_processes(self):
        result, calls = self.notification_fixture()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        phases = [call for call in calls if call[:3] == ["shell", "am", "instrument"]]
        self.assertEqual(5, len(phases))
        access, prompt = "dev.distillpod.android.NotificationAccessDeviceTest", "dev.distillpod.android.NotificationPromptDeviceTest"
        for command, (phase, test) in zip(phases, [("recovery", access), ("verifyRevoked", access), ("runtimeGrant", access),
                                                   ("promptDeny", prompt), ("promptGrant", prompt)]):
            self.assertEqual(test, command[command.index("class") + 1])
            self.assertEqual(phase, command[command.index("notificationPhase") + 1])
        revokes = [i for i, call in enumerate(calls) if call[:3] == ["shell", "pm", "revoke"]]
        self.assertEqual(4, len(revokes))
        self.assertLess(revokes[0], calls.index(phases[0]))
        self.assertLess(calls.index(phases[0]), revokes[1])
        self.assertLess(revokes[1], calls.index(phases[1]))
        # Each in-context phase starts from a revocation made after the previous process ended.
        self.assertLess(calls.index(phases[2]), revokes[2])
        self.assertLess(revokes[2], calls.index(phases[3]))
        self.assertLess(calls.index(phases[3]), revokes[3])
        self.assertLess(revokes[3], calls.index(phases[4]))

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
