# Transcript lifetime recurrence (#81)

Base: current main `ea262b71ef9de6219620a605143be0027ce76db7`.
Worktree: `distillpod-issue81-recurrence`, branch
`fix/81-transcript-parent-recurrence`. #89 is parked and unassigned; this is
the session's sole active issue. Release and plugin work remain owned elsewhere.

## Evidence and scope

The planned read-only cache benchmark on head `6ebff21` (tree identical to
main) passed four startup cases, then ordinary acceptance crashed during
`CloudQueueOrderingDeviceTest.cancelledResolutionDoesNotStopThePersistenceWriter`.
The exception is a transcript foreign-key violation in `writeTranscript` through
transactional `putTranscript`, uncaught in a Main.immediate coroutine. Subsequent
acceptance phases did not run. Raw evidence remains private; the affected run is
[42880697507](https://github.com/andrepaim/distillpod/actions/runs/42880697507).
Earlier PR and merged-main checks passed and retain those results. This new
failure matches #81's original scenario and failure class. Cache causality,
the logical writer and organic production incidence remain unestablished.

The earlier conditional startup transition remains intact. Pause, missing-model
handling and cancellation cleanup still read a transcript then upsert a copied
snapshot. Parent deletion can make that write fail; a concurrent revision,
terminal state or checkpoint change can be overwritten. Cancellation cleanup
can also mask the original cancellation with its database failure. These are
testable candidates, not identification of the historical writer.

## Test-first correction

1. Preserve failure artifacts and review all transcript writers and fixture
   cleanup. Keep the serialized restored-queue baseline and ordering assertions.
2. Use existing DAO injection and real Room operations to hold a returned
   snapshot while another writer removes its parent or changes revision, state
   or checkpoint. Require meaningful red results before correction. Assert no
   resurrection, lost checkpoint, replaced revision or terminal-state regression;
   assert cancellation retains the exact original exception and later valid work
   remains possible.
3. If confirmed, use narrow conditional state-only updates with revision and
   allowed-state guards. Retain strict upsert/FK validation and every untouched
   transcript field, including local age. No global FK catches, sleeps or waived
   assertions. Audit other writers; add bounded diagnostics if the actual hosted
   caller remains unknown. Update the plan when evidence changes scope.

## Risk and completion

Persisted lifetime and automatic/background processing require both default and
voice relevant/full tests, lint/APKs, Python/data/security checks, affected real
emulator queue/lifetime/startup cases and complete hosted acceptance. Runtime
changes also require a dedicated offline isolated S22 protocol on the final
revision, with verified immutable APK hashes, package IDs, signing identity,
instrumentation target and permissions under the exclusive device lock.
Preserve the production app and data; emulator-only destructive fixtures must
never target the phone. No network/security/account/credential changes or release.

Review current-main integration, pass every required exact-head check, merge
normally and verify selected merged-main plus independent security. Reassess
#89's cache benchmark on corrected code, recording failed/skipped/not-run phases
accurately. #97 remains a separate unresolved JVM diagnostics investigation.
