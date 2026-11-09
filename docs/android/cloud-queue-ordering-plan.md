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
