# Transcript parent lifetime (#81)

Base: main74fb57f. #58 head7f446f6 has a confirmed hosted process crash in
LibraryDao.putTranscript during the first cloud-queue ordering case. Startup4
and12ordinary tests passed; the failing episode and logical writer are absent.
No unchanged-code retry is authorized by this evidence. Organic production
incidence, introduction version and a causal link to Shows/#58 are unestablished.

## Intended behavior and diagnosis

Deleted episodes must not be recreated by stale transcription work, crash the
service, or stop subsequent valid work. Superseded revisions, user pause and
completed checkpoints must remain authoritative. Preserve original unexpected
failures and existing queue/order/assertion safety.

A candidate is processChunk's startup read/upsert, before its processing error
handler: the parent can disappear after the read. This is a hypothesis about the
hosted writer until controlled evidence and suite validation establish more.
Audit surrounding fixture cleanup and service/worker ownership too; a test
cleanup fix must not silently hide an actionable runtime failure.

## TDD steps

1. Preserve hosted logs privately. Add an internal DAO-injection constructor
   while retaining existing public constructor/trailing-lambda calls, with the
   real graph DAO as the production dependency. This extraction changes no
   startup behavior and allows real Room operations with a controlled read gate.
2. Add deterministic tests that hold a fetched startup snapshot, delete its
   parent or replace/pause that revision, then release the worker. Confirm a
   behavioral red against the old upsert, retaining the original exception.
   Assert absence of recreated rows, no stale foreground/progress publication,
   preserved current revision/checkpoints, and successful subsequent valid work.
3. Only after confirmation, make the smallest atomic conditional transition
   supported by those tests. A missing, paused, terminal or wrong-revision row
   must be a no-op. Do not globally catch foreign-key errors or replace unrelated
   processing/recovery logic. Review cancellation and retry races again.
4. Validate original automatic-transcription and queue fixtures together on an
   owned emulator. Add bounded synthetic evidence if the full-suite crash has
   another writer; do not call an unrelated reproduction the sole historical
   cause. Preserve primary failures and meaningful fixture ownership barriers.

## Components, compatibility and validation

Likely components: TranscriptionRepository, a LibraryDao conditional mutation,
focused real-Room tests, and dedicated Android lifetime acceptance. No schema,
version, backup, cloud deployment or release change is intended. Preserve public
constructor call compatibility and serialized queue baseline/ordering logic.

Risk is background processing and persisted-data lifetime; require both local
configurations' relevant/full tests, lint/APK builds, Python/data/security checks,
affected real emulator cases and complete hosted startup/ordinary/teardown/sync/
voice phases. Runtime corrections also require a dedicated offline synthetic S22
protocol from the final clean revision, with immutable hashes, isolated IDs,
matching signing identities, target/permission checks and exclusive phone lock.
Never run emulator-only destructive fixtures on the production phone package.
No personal content, settings, accounts, keys or network changes are authorized.

Record actual results and limitations separately. Review current origin/main,
require all five exact-head checks, merge a separate PR normally, and verify all
five merged-main checks before closing #81. #58 remains blocked until the
correction is integrated and its actual combined revision is validated.

## Controlled evidence and correction

Five real-Room tests first failed against the unchanged startup upsert: parent
deletion reproduced SQLite's foreign-key exception; paused, completed and
replacement revisions incorrectly reached foreground startup; a same-revision
checkpoint advanced to5678 was overwritten with1234. The gate holds a real DAO
read result while another real DAO operation changes the row. It introduces no
synthetic database exception. Original source patches, reports and logs remain
private. This confirms the startup race, without uniquely identifying the
historical hosted writer or proving organic production incidence.

Startup now uses a conditional UPDATE requiring the same revision and a queued
or processing state. It changes only state/error, so it cannot insert a deleted
row or restore an older checkpoint. A rejected transition exits before progress
or foreground publication. Existing error handling, retry/cancellation controls,
queue behavior and public constructor calls remain. Internal DAO injection keeps
the controlled interleaving testable without changing production dependencies.

The five cases and existing automatic-transcription/service/resume/playback tests
pass locally. Dedicated guarded Android cases cover the same outcomes using real
platform Room/SQLite; a later valid request must still reach waiting-model state.
Those cases establish data/control safety, not historical OEM foreground denial.
Full configurations, emulator/S22 and hosted/main gates remain pending.
