# Android Auto validation

Date: November 5, 2026. Branch: `feat/android-auto`, worktree `/home/andrepaim/src/distillpod-android-auto`. The feature includes social-sharing baseline `93a2238`, retaining the preview already installed on the S22.

## Local checks

- Full integrated JVM suite: **288 passed**, comprising 85 core and 203 app tests, with no failures/errors/skips.
- Android lint, debug APK, instrumentation APK and release APK assembly passed.
- Python release/bootstrap/result-parser suite: **11 passed**, including the updated bootstrap fixture that exercises restart-phase invocation.
- Emulator: dedicated `DistillPod_Auto_Test`, API 36.1, x86_64, serial `emulator-5570`. The existing emulator and owner phone were excluded from synthetic fixture runs.
- Final browser suite: **3 passed**; modern browse/select/resume, legacy subscribe/browse without an Activity, and subscribed show-list refresh.
- Restart verification: **1 prepare + 1 verify passed** in separate instrumentation processes with an intervening force-stop. Position was restored paused at the checkpoint.
- The browser test also checks standard seek increments, actual legacy custom-command −10s/+30s jumps, authoritative ID resolution, paused capture and preservation of the selected episode.

Local logs include `/tmp/distillpod-auto-final-suite.log`, `/tmp/distillpod-auto-car-seek-green.log`, `/tmp/distillpod-auto-final-device2.log`, `/tmp/distillpod-auto-ci-device.log` and `/tmp/distillpod-auto-python-check.log`. The checked-in receipt contains synthetic test outcomes only. One review run hit three timeouts in existing Drive-backup tests; the focused recheck and complete final rerun passed without changing backup code. An external shell-UID content-provider read also successfully decoded the cached JPEG placeholder.

## Phone and Desktop Head Unit

Phone: Samsung S22 Ultra, SM-S908E. Android Auto: `17.9.664044-release`. DHU: official 2.0 Windows build, using ADB tunneling to port 5277. Android Auto developer mode, Unknown sources and the head-unit server were enabled for private-build testing.

The signed `0.10.1-alpha.1-auto-preview`, code 19, was installed with replacement. Certificate matched the existing app, the existing Firebase client resources were preserved, paid content remained disabled and the app was non-debuggable. Private JSON backup comparison preserved every library table and preference; one playing episode advanced normally between snapshots. Existing social drafts were preserved.

The projected launcher displayed DistillPod and opened all four library sections. Selecting an existing episode loaded the expanded car player with cover art, play/pause, −10s, Distill and +30s. Head-unit play/pause worked. The real host exposed a missing permission for legacy seek commands; a failing unit test and additional device assertions were added, then the commands were granted to trusted controllers. The corrected preview was installed.

Further exact seek and capture checks on the corrected phone build are pending: a concurrent task subsequently replaced it with `0.10.1-alpha.2-social-preview`, which lacks the automotive service declaration. Launcher disappearance after that replacement was confirmed against the phone's installed version and service query, rather than treated as a feature regression. Phone installation coordination is required before completing the final head-unit pass. The DHU connection and ADB forward were closed afterward; Android Auto developer mode/Unknown sources remain configured for the next test.

Personal screenshots, APKs, signing/configuration verification and library snapshots are stored under the private path recorded in `/tmp/distillpod-auto-s22-dir`. No personal screenshots or transcript content are checked in.

## Remaining acceptance gates

- Corrected-build head-unit seek/capture, fresh thumbnail refresh, reconnect and short screen-locked playback on the reserved phone.
- Actual car USB validation; wireless Android Auto if the owner's car supports it.
- Actual Gemini/Assistant speech recognition and app-name routing in the phone's configured language. Synthetic request-matching tests pass; a recorded speech attempt through DHU was inconclusive.
- Real 60-minute screen-locked playback, navigation/call/Bluetooth interruptions and target performance measurements with 10,000 episodes.
- Public Play car-quality review and broader device/host coverage.

Desktop Head Unit validation is not actual vehicle validation. These pending checks do not invalidate the completed local/service tests, and are not marked as passed.
