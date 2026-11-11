# Playback audio ownership for issue 60

This increment protects managed local audio during playlist admission and loader
use. It is a prerequisite for bulk deletion and retention; it does not complete
[issue 60](https://github.com/andrepaim/distillpod/issues/60). The transcription
reader safeguard is already on the base, c35904c.

## Behavior and implementation

- Register a revocable generation before checking the managed file on IO. Do not
  wait for the transfer mutex, which can be held by an unrelated network download.
  Removal revokes the generation and prevents new selections while reserved.
  Failed stat discards only the matching, unowned generation. Weak references
  allow abandoned request tags to expire.
- Put the generation and remote fallback in an in-process MediaItem tag. A
  synchronous ForwardingPlayer claims queue ownership before forwarding every
  incoming playlist overload. A revoked selection uses its fallback at admission,
  before source creation. Keep metadata and start position.
- Reconcile ownership with the actual playlist after the outermost mutation,
  including partial failures, duplicates and nested callbacks. Keep queue leases
  after stop; release them after removal or terminal player release. Detach leases
  before notifying consumers so synchronous callbacks cannot corrupt the map.
- Give each local loader a separate reader lease through close. A removed loader
  that opens after revocation fails with a bounded IOException. Never reinterpret
  extractor offsets against a different remote encoding. Open/close failures must
  release their leases while preserving the original failure.
- Reserve all deletion paths atomically against queue and reader ownership,
  invalidate prepared selections, and perform filesystem IO outside the short
  registry monitor. Retain existing Room, persisted-queue and processing guards.
  Orphan and failed-download cleanup must also respect ownership.
- Prepare selections for all session resolution routes, resumption, startup and
  backup restore, cloud queue application and download-source refresh. Preserve
  requested video, diagnostic clips, current position and the serialized restored
  queue baseline. Do not compare the database queue on every playlist event.

## Acceptance and validation

Use meaningful red/green tests for admission/deletion in both orders, stale and
missing generations, independent files, duplicate/move/replace operations, all
incoming playlist overloads, nested mutation and release, partial mutation
failure, source open/read/close failure and cancellation. Test real Room/file
removal and orphan cleanup, typed blocks, rollback and unchanged listening and
transcript data. Verify all session resolution routes and video intent.

Run both default and voice core/app unit suites, lint and APK builds, Python and
security/data guards. Require all hosted PR checks and emulator phases on the
final head. On the owned emulator, exercise the actual MediaSession queue and
controlled late-loader overlaps using real ExoPlayer and FileDataSource. Run the
same three synthetic tests on the S22 with an immutable, separately installed
offline playback60 APK pair. Verify hashes, package IDs, signing and instrumentation
target before installation; remove only that verified pair. Preserve production
package and data. Review and integrate current main, merge normally, and verify
combined-main CI. Do not publish a release or change release metadata.

## Risk assessment and limits

The primary risks are deleting queued/read audio, leaking ownership, changing
queue/progress ordering, losing video intent, blocking the player looper and
misapplying a fallback after extraction starts. Synchronize only registry counters;
keep file checks on IO and player changes on their existing application looper.
Physical validation is required for Android service playback and file-reader
cancellation. Synthetic S22 tests establish the exercised API36 file/Media3
behavior, not every OEM background scheduling or audio-route condition. No schema,
signing, upgrade or backend changes are intended. A required failed/unavailable
check remains a blocker; record exact revisions and evidence before integration.

Bulk controls, transcript suppression, policy/grace settings and scheduling remain
subsequent work within the authorized parent issue.
