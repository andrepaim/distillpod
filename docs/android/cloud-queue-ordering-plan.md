# Explicit cloud choice and queued playback writes (#75)

Base: current main `1e45c6e`; #74's test scheduling fix is included. Actions
startup is externally blocked by incident tp8cghg7tlc4. No merge/release gate is
waived. This session owns the local investigation; #74/#56/#72 await main CI.

## Evidence and contract

Main a7422e9 reached player/controller [a,c,b] but persisted [a,b], where [c,b]
was expected. Replaying the unchanged production decision function permits that
result if an older local boundary writes after the cloud transaction. A deterministic service regression now establishes that interleaving on the
extracted existing operation (see evidence below). The real caller is SubscriptionSyncUi's explicit other-phone choice:
PortableLibrarySync.resolve(false), acceptCloudQueue, then view activation.
Published affected versions are unestablished; the confirmed revision is main.

A completed valid cloud choice must retain the selected persisted cloud order
without echoing the temporary active item, preserve current audio/progress/play
intent and preserve later explicit local edits. Older queued snapshots must not
republish an earlier choice. Periodic/source-only boundaries must not overwrite
an import. Keep queuePersistenceDecision's serialized restored baseline and its
regressions; do not replace it with per-playlist-event database comparisons.
If a conflict changes or a request fails, report failure and retain valid local
and remote copies; do not report a completed choice with mismatched state.

## Investigation and TDD increment

1. Extract the existing UI operation behind an internal begin/completion API
   without changing its resolve-then-apply behavior. Add a service-writer barrier
   that acknowledges processed earlier messages, not durability/success of a
   swallowed write; data assertions remain necessary. Route this private command
   only to a same-package, same-UID controller. Retain other car/media commands.
2. Add a disposable-emulator regression: establish an actual service/Room seed,
   hold a Room transaction, enqueue another item and observe actual ExoPlayer
   capture while the writer cannot commit, then issue the explicit cloud choice.
   Release the transaction, await completion and the ordered writer barrier, and
   assert actual service/controller/persisted queues and unchanged active audio.
   Use the real isolated conflict-resolution path where possible. Record a red
   behavioral result before fixing ordering; a missing API alone is insufficient.
   Keep a settled import case, without using a sleep to hide the adversarial case.

## Candidate correction, only after confirmation

Extend the existing writer's ordered message stream with an explicit import
operation and a processed-prefix barrier. MediaSession receives the local import
request after earlier commands, marks imports pending on its main thread, then
queues the operation. Process older messages first; resolve and activate the
valid cloud choice, apply it to the actual service player, and establish its
full baseline without publishing the temporary active item. Queue snapshots
captured while import is pending must not later publish an intermediate order;
their listening checkpoints remain independent. Later commands after completion
must persist normally, including clear and restored-queue clear.

Keep callback/capability tokens local, single-use and bounded. An untrusted or
wrong-UID controller cannot trigger resolver execution. A cancelled request that
has not started must not resolve; once accepted processing begins, the service
owns completion so caller cancellation cannot strand a committed choice. On
pre-commit failure, preserve newer local intent; on post-commit apply failure,
retain the cloud DB choice and record the actual player baseline without
republishing it as a local edit. Service destruction must fail pending requests
and leave persisted data recoverable. Preserve original failures. Review these
paths before implementation rather than silently dropping pending work.

The internal resolver callback permits real PortableLibrarySync execution with
synthetic isolated-demo state in tests; no real account, credential or remote
provider is used. No schema, backup format, backend deployment or release change.

## Required evidence

Use TDD for ordering, authorization, later edits/clear, failed/cancelled requests
and service teardown. Run relevant local unit/default/voice/lint/APK/security
checks and actual emulator acceptance on the final combined code. Runtime
persistence changes also require a dedicated offline, package-guarded S22 probe
with immutable APK hashes/IDs/target/signers verified before install. Never run
the general emulator fixtures on the phone or touch production app/data/settings.
All five exact-head CI checks, current-main integration, normal PR merge and
successful combined-main CI remain required after Actions recovers. Record
unavailable evidence and unconfirmed causes; keep the issue open if blocked.

## Reproduction receipt

On 2026-11-09, an API35 disposable emulator ran the new real-service regression
against main1e45c6e plus the behavior-preserving extraction and processed-prefix
barrier. Source receipt SHA256774e18ca6983058e40ea28d1c4bd47c9ac101dcadbd491ce285397523f177745.
The actual player reached the imported order; the settled DB assertion failed:
expected[c,b], actual[a,b]. One test ran, one assertion failure; cleanup reported
no secondary exception. This is a behavioral red, not a compile/precondition
failure. Immutable APK hashes, source status and raw synthetic result remain in
private local diagnostics. The extraction's media-session authorization suite
passed14tests; Android test compilation and both APK builds passed. These are
incremental investigation results, not final acceptance. No phone test ran and
no runtime ordering correction has yet been validated.

## Correction review and incremental evidence

The candidate now sends a same-package/same-UID, opaque single-use cloud-choice
request into the existing writer channel. Older captured writes finish first;
resolution and queue activation occur in one Room transaction. Applying the
chosen order preserves the actual current episode, position, play intent and
video source. Pending intermediate snapshots retain checkpoint processing but
cannot publish a queue. Later explicit local edits supersede the older cloud
choice; the writer establishes its serialized baseline before releasing the
pending choice and capturing a final boundary. The restored-baseline decision
function and its regressions are unchanged.

Caller cancellation before ownership prevents resolution. Once processing
starts, the service owns the short operation; caller cancellation cannot cancel
its transaction. A resolver CancellationException fails that request while an
active service writer continues. On failure after commit, the chosen DB queue
remains and the actual player baseline prevents a source-only boundary from
publishing the earlier order. Service destruction fails the outstanding request;
its dedicated emulator lifecycle acceptance passed (see below).

A clear-while-pending test found the existing Room observer restoring an older
local order while the writer was blocked. A bounded, count-only synthetic trace
confirmed that restoration (rather than attributing it to controller optimism).
The observer now refuses restoration during a pending choice and rechecks the
logical queue revision after each suspended read. Its serialized restored
baseline remains. Temporary diagnostic logging and one-off failed-fixture
recovery code were removed; synthetic raw evidence is private.

Seven API35 emulator scenarios passed on candidate source receipt
c1b5d2ee8d62: older captured write/cloud choice plus later move/clear; clear while
pending; cancellation before ownership; caller cancellation after ownership;
resolver rejection with original failure; resolver cancellation without stopping
the writer; and application rejection after the cloud commit. Incremental local
checks passed22tests (14media-session,5baseline,3ownership). These results are
not final-head/full hosted/physical validation. Full default/voice checks,
service-teardown acceptance, a dedicated offline phone probe and normal CI/merge
gates remain. A settled ordinary-import case now uses the same real ordering
entry point and processed-prefix barrier instead of a timing sleep. The obsolete
unfenced repository import entry point is removed.

## General clear/restoration race

After Actions recovery, main1e45c6e's one recovery retry passed four checks but
failed ordinary emulator acceptance:83tests,1 RestoredQueueDeviceTest failure,
`seed cleared`, expected0/controller1/persisted0/actual service1 (paused). No
unchanged-code retry is being used. A new controlled later-clear regression
then failed against the first cloud-choice candidate: the actual service did
not stay empty after an older queued local write/Room emission. This establishes
a second runtime restoration race; the previous seven-case success did not
cover this general case.

Automatic restore requests now join the same ordered writer channel. The owner
re-reads the current persisted queue after preceding writes, checks the local
queue revision and actual empty-player state, and records restoration through
the existing serialized restored baseline. It does not directly apply the
possibly stale Room emission. Explicit imports also wait for startup restoration
before resolving, preventing startup from reapplying an earlier empty-player
snapshot over a chosen cloud order. No per-playlist database-baseline comparison
has been introduced and the existing baseline function/tests are unchanged.

Candidate receipt28e485e044c2 passed11 API35 cases:8 ordering/failure scenarios,
settled ordinary import, restored-queue clear and existing queue-controls UI.
A separate instrumentation process then passed actual service destruction:
queued resolution did not execute and its completion failed with the original
service-stop cancellation. This separate phase is included in hosted acceptance;
it cannot strand the closed controller in later ordinary tests. The dedicated
phone protocol is restricted to package validation.queue75, refuses Internet
and microphone permissions, requires empty fixture data and runs four offline
queue/cancellation/clear scenarios. It has not run on the S22. The phone was
connected but asleep at the last read-only probe.

The earlier full default/voice gates each passed99core+537app, lint and APKs,
but precede the general restoration correction and must not be represented as
final acceptance. Re-run final combined local checks, targeted emulator tests,
physical protocol and all five hosted checks on the committed correction.

## Committed runtime validation and runner correction

Commit47289e8 passed both full default and voice local gates:99core+537app each,
zero failures/skips, lint and APK builds. The hosted build job then exposed an
outdated shell-bootstrap test expecting7phases after the dedicated shutdown
phase made8. This was a runner-contract test failure, not an Android test
failure. The correction verifies the exact phase ordering/exclusions and adds a
black-box failure-path case: a failed shutdown probe must retain its log, stop
later phases and still clean the browser fixture. The CI artifact path now
retains both ordinary and shutdown instrumentation logs. Its retention assertion
was observed red before the glob correction and green afterward. No Android
runtime code changes accompany this runner-only amendment; final-head hosted
checks and physical acceptance remain required.
