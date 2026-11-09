# Voice readiness (#57)

## Plan and acceptance
Return a typed, content-free readiness reason for missing transcript, stale/audio
mismatch, online-only source, empty past-only selection, and coverage gaps.
Keep partial transcripts eligible when the existing recency/window/size rules pass.
Read context in a database transaction. Preflight the current playback position
before pausing, then re-read for the acquired lease before provider/audio work.
Expected readiness is feedback, not an internal exception report. Retry clears
feedback. A readiness race restores the acquired lease without optional rewind.

## Scope and risks
VoiceContext, VoiceCoordinator, application wiring, minimal session UI copy,
playback lease restoration, diagnostic reason allowlist and tests. No settings
changes, schema changes, keys, accounts, releases or production-data fixtures.
Issue #21 owns unrelated VoiceUi settings changes; preserve them. Coordinate
PlaybackRepository/application overlap with queue/transcription owners.

## TDD and validation
First add failing readiness classification and preflight/race/retry coordinator
tests. Run focused tests, implement, run full relevant unit tests/lint/build.
Retain existing cancellation, context selection and playback guard regressions.
Device checks are required: isolated synthetic app on emulator and phone, no
real provider or microphone calls for unavailable context, visible specific
feedback, no pause on preflight failure, exact lease restoration after race,
and successful synthetic retry. Test paused/playing and optional rewind states.
Coordinator approval of protocol and exclusive device use precedes device work.
No emulator-only destructive fixtures on phone. Preserve production app/data.
Record revision, scenario results and any remaining blockers; no merge until
required evidence is obtained and coordinator integrates current main.

## Execution evidence and integration
Initial TDD compile failed on the new readiness/preflight/restoration API as
expected. Initial implementation passed 99 core and 501 app tests plus lint.
A later added diagnostic test incorrectly compared exception object identity;
coroutine stack recovery copies exceptions. It now asserts exactly one report
with the original exception type/message while keeping user feedback sanitized.
All checks will be rerun on the combined code before acceptance.

Reviewed #54 commit bb51e8c was integrated by normal merge, without conflicts.
Empty current queue is now typed readiness both before and during lease acquisition.
VoiceReadinessDeviceTest is part of ordinary androidTest and hosted emulator
acceptance. Its real-graph synthetic tests permit only a disposable emulator or
the exact isolated voice57 package. The dedicated init/manifest builds that
phone package without Internet or microphone permission; it is never applied
to production builds. No devices have been used yet; emulator and phone evidence
remain required. The coordinator owns final current-main integration and merge.
