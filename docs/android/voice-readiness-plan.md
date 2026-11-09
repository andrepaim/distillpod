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
