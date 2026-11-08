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
