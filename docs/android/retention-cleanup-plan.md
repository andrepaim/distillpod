# Bulk cleanup and opt-in retention (#60)

Status: the owner confirmed transcript deletion semantics on 2026-11-09: delete local copies, preserve cloud copies and saved insights, and suppress automatic recreation until explicit Prepare or Restore. The local age-provenance increment is merged in PR #101 as `ce94bce`. It records local origins and adds schema 12 without enabling cleanup. Bulk deletion, suppression, policy UI and scheduling remain unimplemented; #60 stays open. #58 is integrated.
Issue: https://github.com/andrepaim/distillpod/issues/60
Age increment base inspected: `d303258`; repaired combined main verified: `70a3dab`. No production/device content is used or deleted in validation.

## Scope and acceptance

Add separate clear-all local episode audio and local transcript controls, with a live preview, explicit confirmation and counts of removed, protected and failed items. Keep episode metadata, subscriptions, models, playback progress, saved insights, analysis, messages and research. Audio and transcript cleanup are independent. Automatic cleanup is OFF by default for both existing and new users. No cloud deletion, account change, release or APK publication.

## Proposed increments

1. Local download/transcript age provenance and additive migration, with no cleanup enabled (current increment).
2. Audio ownership exclusion, confirmed bulk audio APIs/UI and synthetic race/device acceptance. Preserve #58 guards and queue baseline.
3. Transactional local transcript suppression, explicit Prepare/Restore intent, final-writer/import races, and confirmed bulk transcript UI. Preserve cloud and saved outputs.
4. Actual completed-playback provenance, independent opt-in rules, unknown-age grace, confirmed policy changes, deferred sweeps and integrated acceptance.

Each increment requires a current-head PR and applicable gates. Keep #60 open until
all requested behavior and acceptance criteria pass.

## Chosen timer semantics

Each rule independently offers Off, 1, 7, 30 or 90 days. One day means 24 elapsed wall-clock hours, not a calendar-day boundary. At exactly origin + days the item is eligible. Invalid, missing or future origins fail closed. Arithmetic must not overflow.

Audio age starts when that local download completes, never publication date. Transcript age starts when its local revision is created/imported, not each progress update. Replacing a revision or downloading a new copy starts a new age. Completed-playback audio age starts at the first actual playback end recorded for that copy; manual/synced completed flags do not fabricate playback completion. Replaying must not continually postpone an already recorded completion. If both audio policies are enabled, the first expiry wins. Transcript expiry is independent of playback.

Existing content has unknown origins: initialize unknown age origins only when the corresponding rule is explicitly enabled, granting its full chosen interval. Do not infer historical completion from lastPlayedAt. Disabling rules preserves content; re-enabling known origins may make old content eligible and requires an informative confirmation. Shortening retention also previews currently eligible content and requires confirmation. Confirmed bulk previews capture IDs/revisions so newly arriving/replaced items are not silently included.

## APIs and dependencies

#58 retains deleteDownload/discardPausedPartial and throws AudioDeletionBlocked(reason) only for expected protection. Invalid path and I/O errors remain unexpected. deletionBlock is advisory; actual deletion rechecks under episodeTransfers, episode mutex and Room transaction. Bulk code catches only this typed refusal as protected and reports other failures separately. Never weaken active, queued, downloading or transcribing protection.

Transcript cleanup needs serialization with TranscriptionRepository.controls, active jobs, queued work and all transcript writers. Remove transcript row, segments and coverage in one Room transaction; inspect AI/capture dependencies and defer queued/running consumers. Preserve saved outputs. Do not claim atomic file+database deletion: filesystems and SQLite cannot share a transaction. Reconcile partial-file failures and make repeated deletion idempotent.

Confirmed sync risk: FinishedContent.restore can reinsert absent local transcripts, and automatic playback preparation can regenerate them. Implement the confirmed local suppression contract before changing this path. Do not delete remote artifacts or silently disable user transcription preferences. The writer/schema design remains a dependency, not an implemented guarantee.

Proposed scheduler: unique daily WorkManager sweep plus app-open check, serialized and rechecking the latest settings before every item. Protected items remain eligible for the next sweep. Android may delay execution, so describe eligibility rather than promise exact-time deletion. This is the planned scheduling choice; it requires physical background validation before shipping. Avoid overlapping #56 transcription recovery edits and #54 queue work; integrate their final revisions.

## Risk assessment and validation

Bulk operations are high data-integrity risk despite local-only scope. Tests must cover confirmation/cancel/repeated clicks; newly downloaded/replaced items after preview; queued/playback/download/transcription/AI guards; I/O failures; transaction rollback; interrupted/repeated cleanup; preserved subscriptions, saved outputs and progress; refreshed storage counts. Synthetic emulator fixtures only.

Timestamp migration and scheduler add upgrade/background risk. Test migration with unknown timestamps and unchanged default settings, import/redownload origins, timer equality and +/-1 millisecond, clock rollback/future values, disabled/independent rules, policy changes mid-sweep and protected retry. Physical-device evidence must use an explicitly approved isolated package and synthetic data; never uninstall/clear production app, run destructive fixtures there or use personal content. Required unavailable checks block merge.

Use TDD for logic, default and voice unit tests/lint/APKs, applicable emulator acceptance, security scan and final combined-main review. Serialize heavy builds with flock /tmp/distillpod-dot-full-build.lock and --max-workers=2. Record exact revision and distinguish passed, failed and not-run checks. Coordinator owns merge. PR wording: Addresses #60, not automatic close.

## Read-only dependency findings

- `data/OnlineTranscripts.kt`: EpisodeTextResolver.complete can expose OnlineTranscriptEntity JSON even after the ordinary transcript row is removed. Cleanup must cover both local representations, not merely segments.
- `sync/FinishedContent.kt`: restore imports transcript/segments/coverage together with analysis/messages/research; an absent transcript is importable. Blocking the entire artifact would also suppress unrelated saved outputs, which is unacceptable.
- `sync/ArtifactSync.kt`: local compressed artifact files bundle transcript text with saved outputs. Retaining these is not the same as erasing all transcript text; deleting these files or cloud objects is outside the proposed scope. UI must distinguish active local transcript storage from retained saved copies.
- `sync/SubscriptionSyncAccount.kt`: explicit account restore passes replaceRevision=true. `PortableLibrarySync.kt` also uses that flag in conflict resolution, so it is not sufficient by itself to identify explicit transcript re-prepare intent.
- `data/TranscriptionRepository.kt`: prepareAutomatically, explicit prepare, playback and capture callers share preparation paths. A local suppression marker needs explicit intent separation; do not infer it from prepare being called.
- `data/ShowAutomation.kt`: incomplete jobs can enqueue new downloads/transcripts when content disappears. Defer cleanup of ongoing jobs and resolve interaction with user automation settings before implementation, rather than silently changing those settings.
- `data/MediaRepository.kt`: #58 preserves existing serialized deletion and typed guards. ActiveFiles playback/queue/acquire transitions are not an atomic exclusion lease with deletion. A periodic background sweep must resolve this TOCTOU risk before it is safe to ship.

Confirmed transcript contract: deletion is local, cloud copies and saved insights remain, and passive import/automatic preparation must not recreate the deleted transcript until explicit user Prepare or Restore. The owner answered “Yes, use those semantics”; this decision no longer needs clarification. A dedicated per-episode local cleanup record remains the proposed implementation, with explicit intent separated from passive restore/conflict resolution. Audio-marker interactions remain part of the existing design review, rather than an implied extension of this transcript decision. Local suppression, automatic sweeps and transcript deletion remain unimplemented. The separate age-only schema 12 increment records origins; it does not implement cleanup.

## Preparation and import intent review

Read-only review against current main `a7422e9` on 2026-11-09, including #56's
startup cancellation/retry changes. That historical review is retained below; main through `d303258` and #58 are now
integrated. These are implementation requirements, not implemented behavior.

| Entry point | Required treatment after local transcript cleanup |
| --- | --- |
| User Prepare in `features/AppViewModel.kt` | May explicitly clear suppression and start a new revision under the existing preparation controls. |
| Playback's `prepareAutomatically` | Must respect suppression without queuing a replacement or changing user settings. |
| `data/ShowAutomation.kt` preparation callback | Currently calls the same `prepare` API as user actions; distinguish its automatic intent before adding suppression. |
| `DistillPodApplication.kt` capture preparation callback | Must not implicitly count as explicit Prepare merely because a capture needs text. Preserve saved/pending output and expose the missing preparation action. |
| `TranscriptionRepository.resumeModel`, called by `work/AppJobs.kt` | Model completion is not explicit Prepare. A previously captured waiting-model row must not recreate text after cleanup wins a race. |
| Passive `sync/ArtifactSync.kt` and subscription projection | Keep suppression and import eligible saved outputs independently of the transcript portion. |
| `PortableLibrarySync.resolve` with `replaceRevision=true` | Conflict resolution is not, by itself, an explicit transcript Restore. |
| Explicit saved-copy restore in `SubscriptionSyncAccount.kt` | May clear suppression only as part of a successful, validated Restore; a rejected artifact must leave suppression intact. |

Do not infer user intent from the existing `prepare` or `replaceRevision`
parameters. Carry it explicitly to the transaction that changes transcript
availability. Cleanup must remove ordinary transcript rows, segments, coverage
and the selected online representation in the same transaction that persists
the suppression record.
It must preserve episode identities and separately saved outputs. Retained
compressed artifacts can still contain saved transcript text; the confirmation
must explain that this removes active local transcript storage and retains
saved/cloud copies, rather than claiming complete erasure.

Add deterministic races for a model-completion callback captured before deletion,
an online lookup completing late, passive artifact import and explicit Restore
competing with deletion. Require suppression to be checked at the final write,
not only before asynchronous work. Test that malformed or incompatible explicit
Restore does not clear it, and that suppressed artifact projection still restores
unrelated eligible saved outputs. Preserve #56's control-lock ordering and
cancellation protections; do not wait for an old worker to join under that lock.

Before any automatic audio sweep, complete the existing ActiveFiles ownership
design review: checking `busy` and then deleting does not exclude a concurrent
playback/queue/processing acquisition. A last-moment boolean check alone does
not resolve that race. Define and test one exclusion contract for deletion and
all affected acquisitions, without blocking the media main thread on file I/O.
#58 is integrated. Ownership exclusion remains a prerequisite to deletion
implementation; age provenance does not establish that exclusion.

## Isolated policy increment evidence

The pure `core/RetentionPolicy.kt` increment has no callers and performs no deletion, persistence, scheduling or setting changes. It requires explicit timestamps and current time as inputs. Defaults are disabled. Eight regression tests cover off/independent settings, never-played audio, earliest audio deadline, exact expiry boundaries, missing/invalid/future origins, overflow, policy changes and redownload age. At that initial checkpoint, protection/retry/migration/UI integration tests were not yet implemented. Age-specific upgrade evidence is now recorded below; cleanup integration remains pending.

TDD red: `/tmp/distillpod-issue60-red.log`, compileTestKotlin fails only because RetentionPolicy does not yet exist. Green: full core suite passed, including all 8 new retention tests; `/tmp/distillpod-issue60-green.log`. Repository data-guard regression tests (4), secret-rule regression check and git diff --check also passed. At that initial pure-policy checkpoint, no Android APK, emulator, physical-device or CI check had run for #60. That pure increment did not exercise those paths. Subsequent age-only evidence is recorded below; remaining cleanup behavior retains its own validation gates.

## First app increment: local age provenance (2026-11-10)

This independently verifiable increment records when a local audio copy finishes
and when a transcript revision is first created/imported. It adds two non-null
`localDownloadedAt` / `localCreatedAt` columns with zero meaning unknown, schema
12 and an additive 11→12 migration. Old content retains unknown origins; neither
publication dates, last-played values nor cloud timestamps become local ages.
There are no cleanup callers, settings, deletion, suppression or scheduler changes
in this increment. Completed-playback provenance remains a later increment.

`LibraryDao.downloadComplete` records its supplied completion clock atomically
with the new file identity; removing a download or changing its enclosure resets
that origin. Repeating completion for the same copy preserves its existing origin.
Transcript writes remain transactional: the same revision preserves its original
local age (including unknown legacy age), while a new revision starts at the local
write clock, ignoring timestamps carried by imported/copied objects. Inject clocks
at DAO boundaries for deterministic tests. Online-cache provenance is a later increment: existing `retrievedAt` can come
from a backup and must not be mistaken for the current installation's age. The
future eligibility projection must match each origin to the exact representation
and revision it previews.

TDD must demonstrate download/redownload/reset behavior, unchanged progress and
sync/feed metadata updates, same-revision transcript progress/retry/import,
revision replacement and legacy unknown origins. Upgrade tests cover schemas
1–11 and preserve progress, saved outputs, queue, content and Off defaults. The
pure policy still fails closed for unknown/future origins and has no callers.
No local timestamps enter portable artifacts, backups or sync payloads; assert
that contract. Backups retain their existing wire schema by excluding these two
local columns from export/validation, and newly inserted transcript rows receive
the import clock inside the existing merge transaction; skipped/conflicting rows
retain their local age. Do not infer the import age from backup creation time.

Risk: local Room migration and data age provenance, no file deletion or background
execution. Require default/voice unit suites, lint/APKs, all hosted selected gates,
applicable database/feed/sync emulator tests, and the current migration fixture on
the S22 in an offline separately identified package with synthetic databases only.
Verify hashes, signer, package and instrumentation target before installation;
production package/data remain untouched. Device evidence validates SQLite
migration on the affected platform; it does not establish future background or
whole-app production-upgrade behavior. Those later increments retain their gates.

Before enabling audio cleanup, replace advisory busy checks with an exclusion
contract for deletion and playback/processing acquisition, tested under controlled
interleavings. Before transcript cleanup, persist suppression and delete both local
representations transactionally, distinguish explicit Prepare/Restore at every
entry point, and reject late writers at their final transaction. Do not claim those
protections are delivered by age metadata. Keep #60 open after this increment.

Age TDD evidence (before final commit gates): missing fields/clock arguments failed
compilation as expected (`age-red.log`); the implemented age and all-schema upgrade
tests passed (6 cases). A separate backup red test failed because export contained
`localDownloadedAt`; export/import was corrected without changing the portable
format. The focused suite then passed 107 core and 27 app cases (age, migration,
backup, finished-content and feed preservation). These focused checks do not replace
final default/voice, emulator, S22 or hosted validation. Logs remain private under
`distillpod-tools/issue60-retention`.

The first full voice gate at `7086b50` ran 584 app tests: nine whole-record
expectations failed only on the newly recorded age (expected zero); the other
575 passed. Existing cancellation, startup and protected-deletion fixtures now
supply fixed local clocks and retain their full record assertions. Their focused
suite passed after that adjustment. Default/lint/APK stages of that failed driver
are not counted as passed. Final gates must run again on the corrected commit.

Additional final-write boundary found: `LibraryBackup.merge` uses validated raw
SQL rather than `LibraryDao.putTranscript`. The age increment handles its import
clock explicitly. The later suppression increment must also guard this raw import
transaction, including preview-to-merge races; DAO interception alone is insufficient.

## Delivered age increment and evidence

[PR #101](https://github.com/andrepaim/distillpod/pull/101) merged as
`ce94bce69a408a9ff7f71118adae7f49edd936b6`, with the same tree as final tested head
`da44fc6343500ad494e84d1da244972dcd4dcd06`. Schema 12 adds only
`episodes.localDownloadedAt` and `transcripts.localCreatedAt`; all existing
columns, tables and constraints are preserved. Portable backup columns/format
remain unchanged. Unknown legacy ages stay zero; progress/retries retain age;
new local copies/revisions and newly inserted imports use the receiving clock.
No policy settings, deletion callers or scheduler are enabled.

- Default and voice local gates at `cbf4420`: each 107 core and 584 app tests,
  zero failures/errors/skips, lint and main/test APKs passed. Final `da44fc6`
  changes only two opt-in test harnesses; all other tracked inputs are identical.
  Both default/voice main and test APKs reproduced byte for byte on the final head.
- Four startup and three pause probes passed on the owned API35 emulator on
  `da44fc6`, retaining complete record/state/checkpoint assertions with fixed clocks.
- Nineteen affected ordinary API35 cases passed, followed by twelve offline
  age/schema fixtures on the emulator and twelve on the SM-S908E / Android 16
  (API36). Final-head offline main/test APKs reproduced the S22-tested pair exactly.
  No new phone execution is claimed after the harness-only change. Only those
  isolated packages were removed; production package metadata was unchanged.
- Final [PR CI](https://github.com/andrepaim/distillpod/actions/runs/42855461486)
  passed all seven selected jobs. Hosted reports show 107 core and 584 app cases
  with no failures/ignored cases; complete startup, ordinary, cloud, notification
  and voice acceptance passed. The earlier run `42853491189` failed one startup
  zero-age expectation and stopped before later acceptance; its remaining build
  was superseded/cancelled. It is not counted as passed.
- 111 Python cases, repository data guard and corrected secret rules passed;
  their inputs are unchanged by the final harness-only update. Final full-history
  Gitleaks scanned 363 commits with no findings.

Initial merged-main `ce94bce` failed gallery acceptance in
[42858095533](https://github.com/andrepaim/distillpod/actions/runs/42858095533).
[Issue #102 / PR #103](https://github.com/andrepaim/distillpod/pull/103) repaired
that separate test target/readiness path. Repaired combined main `70a3dab` passed
all selected [Android checks](https://github.com/andrepaim/distillpod/actions/runs/42866830087)
and independent [security](https://github.com/andrepaim/distillpod/actions/runs/42866829944),
including ordinary109 and complete later acceptance. The called-security
placeholder is intentionally skipped on direct main push; independent security
passed. Age/runtime and S22 fixture sources are unchanged by that test-only repair;
no new S22 execution is claimed for it.

These device fixtures establish synthetic SQLite migration, local-clock and
rollback behavior. They do not establish a production APK upgrade, future cleanup,
background scheduling or OEM playback ownership behavior. Those later increments
retain their own risk assessments and physical gates. #60 remains open until all
requested cleanup acceptance criteria pass. This work did not create a release.
