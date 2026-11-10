# Voice readiness (#57)

## Plan and acceptance
Return a typed, content-free readiness reason for missing transcript, stale/audio
mismatch, online-only source, empty past-only selection, and coverage gaps.
Keep partial transcripts eligible when the existing 15-second recency rule passes.
Respect merged PR #65: include the entire valid listened prefix, without restoring
the old two-minute window or 12,000-character context limit. Transport frames stay
bounded and acknowledged; provider capacity failures remain separate feedback.
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

## Handoff review and full-context integration

The previously uncommitted tests were saved privately before review. Their first
focused run exposed an incomplete fake-provider reply (completed without audio),
which the client correctly rejected. The fixture now returns a synthetic PCM
answer and asserts that playback receives it only after input closes. All 26
focused context/database/coordinator tests then passed, with no skipped cases.
Coverage includes an old-prefix gap, long ready partial context, malformed,
future and unrelated segments, revision changes, the inclusive 15-second boundary,
full-source acknowledgment before recording, upload cancellation, capacity feedback,
and resource cleanup before lease restoration.

Current main at 2042306 and the reviewed queue revision 5888db1 were integrated by
normal merges. Full combined-revision default/voice, hosted and corrected physical
acceptance remain required; focused tests and the earlier phone run do not replace
those gates. Physical validation remains mandatory because this change affects
playback lease acquisition/restoration and its interaction with real Android media
controllers. Preserve all owner data and use only the dedicated offline package.

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

## Physical run and test-oracle correction
The first physical run on the isolated app at b00949d completed 2 tests:
visible feedback/retry passed; playback restoration failed at a moving-position
comparison between two MediaController instances. Preflight-no-pause and actual
resumption were reached; later retry/rewind/seek/clear scenarios were not reached.
This result is not a full phone pass. The production package metadata was
unchanged and both owned test packages were stopped.

The comparison did not record its values and cannot distinguish real seek from
independent controller position estimates. Production code remains unchanged.
The revised test waits for observer pause acknowledgement, records content-free
lease/observer positions and event counts, and checks zero SEEK events plus
resume policy for readiness restoration. It retains exact paused-position
assertions and requires the configured rewind's exact SEEK target on normal
completion. No tolerance was widened; a new phone run is still required.

## Fresh handoff validation package

The handoff S22 already has the older isolated voice57 app and test app installed.
The fresh-install guard stopped before any replacement. Preserve those packages
and use dev.distillpod.android.validation.voice57handoff with the matching test
ID for this continuation. Both the opt-in build configuration and exact physical
package guard now use that new ID; no broad prefix exception is allowed. Rebuild
and verify both APKs, target, signatures, permissions and hashes before installing.
Production code is unchanged. Required phone evidence remains pending.
