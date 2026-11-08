# Preserve pause while checking for a missing model

## Plan

This prerequisite for the authorized issue queue fixes the Android acceptance
failure where AutomaticTranscriptionDeviceTest expects paused but sees
waiting_model. A worker retains a transcript snapshot while pause changes the
stored row. Its missing-model write must not restore that stale snapshot.

- Reproduce the stale-row ordering with a persisted control result at the worker's
  existing foreground boundary, independently of cancellation. Separately gate a
  real pause to verify cancellation and progress cleanup, without timing sleeps.
- Serialize the missing-model state transition with user controls and re-read the
  current row. Update only the matching, active queued or processing revision
  (publisher fallback can retain queued state).
- Keep normal missing-model behavior covered, plus paused and superseded work.
- Run focused regression tests red before the fix, then unit tests, lint, APK
  assembly, and script checks. CI emulator acceptance and current USB-phone
  validation remain mandatory before integration.

The change does not alter schema, saved segments, model selection, authentication,
owner data, release metadata, or the device-test assertions. Picker navigation and
screen-off media-key failures are investigated separately, not papered over here.

## Evidence

- Baseline regression: the missing-model pass overwrote persisted paused with
  waiting_model. A separate baseline run also restored an obsolete revision over
  its replacement. These failures were deterministic, without timing sleeps.
- Focused AutomaticTranscriptionTest passed after the guarded transition.
- Added queued fallback/progress preservation coverage during review; final full
  local validation is recorded in the pull request.
- No local emulator or owner's phone was used. CI emulator and current physical
  phone validation are still required before merge.

## Other acceptance failures investigated

The failed DocumentsUI hierarchy in run 42533312880 attempt 1 has the picker open
but no visible locations pane. The helper opens navigation once before polling,
so a drawer-opening/layout race is plausible; a missing provider is not proven.
No picker assertions were changed.

The headphone failure is specifically the screen-off system input route after a
successful platform-controller capture. Its 900 ms inter-press delay exceeds the
750 ms duplicate filter. This does not prove the system selected this session;
session-routing evidence or a reproduced failure is needed before a targeted fix.
No headphone assertions or production behavior were changed.

## Isolated physical probe

The opt-in source under app/src/validationTest/java adds three real Android
Room/coroutine tests. It requires exactly
dev.distillpod.android.validation.pauserace before opening the graph. Each case
creates synthetic UUID-scoped rows, saved text and coverage, then removes only its
own fixture. It does not play audio, access an account, download models, change
global device settings, or touch the owner's application.

Use the dedicated-package init-script procedure in
[download network policy](download-network-policy.md), with application ID
dev.distillpod.android.validation.pauserace, test ID
dev.distillpod.android.validation.pauserace.test, and add
android.sourceSets.androidTest.java.srcDir('src/validationTest/java')
inside the app configuration.

Build debug and instrumentation APKs with that temporary init script. Verify both
IDs in the built manifests and confirm both packages are absent on the selected
phone before installing. Run only
dev.distillpod.android.TranscriptionPauseDeviceTest through IsolatedTestRunner,
verify its log with scripts/device_result.py, and remove only these two temporary
packages afterward. Coordinate an exclusive device window before installation or
execution; never bypass the existing emulator-only playback fixture guard.

The probe verifies stale paused/superseded/terminal protection, real in-flight
cancellation, automatic preparation retaining pause, and preservation of current
progress, saved segments and coverage. It does not validate real headset routing,
playback UI, native inference, battery behavior, or release-signed owner upgrades.
Record tested revision, APK hashes and device results separately; preparing or
compiling this probe is not physical-device acceptance.
