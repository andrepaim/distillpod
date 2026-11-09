# Bulk cleanup and opt-in retention (#60)

Status: the owner confirmed transcript deletion semantics on 2026-11-09: delete local copies, preserve cloud copies and saved insights, and suppress automatic recreation until explicit Prepare or Restore. Bulk deletion, schema and scheduling remain unimplemented and depend on #58 integration plus the safety review below.
Issue: https://github.com/andrepaim/distillpod/issues/60
Base inspected: 9528abf. No production/device content is used or deleted in validation.

## Scope and acceptance

Add separate clear-all local episode audio and local transcript controls, with a live preview, explicit confirmation and counts of removed, protected and failed items. Keep episode metadata, subscriptions, models, playback progress, saved insights, analysis, messages and research. Audio and transcript cleanup are independent. Automatic cleanup is OFF by default for both existing and new users. No cloud deletion, account change, release or APK publication.

## Proposed increments

1. Bulk cleanup repository APIs, confirmation UI and synthetic regression/device tests. Depend on #58 typed AudioDeletionBlocked feedback without changing its protection boundary. Keep #60 open.
2. Explicit local timestamp persistence, safe migration and pure retention eligibility logic with injected time. Keep #60 open.
3. Local opt-in settings, confirmed policy changes, serialized deferred sweep, cancellation/retry behavior and complete integrated acceptance. Close #60 only after all acceptance and combined-main CI pass.

## Proposed timer semantics (pending acknowledgment)

Each rule independently offers Off, 1, 7, 30 or 90 days. One day means 24 elapsed wall-clock hours, not a calendar-day boundary. At exactly origin + days the item is eligible. Invalid, missing or future origins fail closed. Arithmetic must not overflow.

Audio age starts when that local download completes, never publication date. Transcript age starts when its local revision is created/imported, not each progress update. Replacing a revision or downloading a new copy starts a new age. Completed-playback audio age starts at the first actual playback end recorded for that copy; manual/synced completed flags do not fabricate playback completion. Replaying must not continually postpone an already recorded completion. If both audio policies are enabled, the first expiry wins. Transcript expiry is independent of playback.

Existing content has unknown origins: initialize unknown age origins only when the corresponding rule is explicitly enabled, granting its full chosen interval. Do not infer historical completion from lastPlayedAt. Disabling rules preserves content; re-enabling known origins may make old content eligible and requires an informative confirmation. Shortening retention also previews currently eligible content and requires confirmation. Confirmed bulk previews capture IDs/revisions so newly arriving/replaced items are not silently included.

## APIs and dependencies

#58 retains deleteDownload/discardPausedPartial and throws AudioDeletionBlocked(reason) only for expected protection. Invalid path and I/O errors remain unexpected. deletionBlock is advisory; actual deletion rechecks under episodeTransfers, episode mutex and Room transaction. Bulk code catches only this typed refusal as protected and reports other failures separately. Never weaken active, queued, downloading or transcribing protection.

Transcript cleanup needs serialization with TranscriptionRepository.controls, active jobs, queued work and all transcript writers. Remove transcript row, segments and coverage in one Room transaction; inspect AI/capture dependencies and defer queued/running consumers. Preserve saved outputs. Do not claim atomic file+database deletion: filesystems and SQLite cannot share a transaction. Reconcile partial-file failures and make repeated deletion idempotent.

Confirmed sync risk: FinishedContent.restore can reinsert absent local transcripts, and automatic playback preparation can regenerate them. Implement the confirmed local suppression contract before changing this path. Do not delete remote artifacts or silently disable user transcription preferences. The writer/schema design remains a dependency, not an implemented guarantee.

Proposed scheduler: unique daily WorkManager sweep plus app-open check, serialized and rechecking the latest settings before every item. Protected items remain eligible for the next sweep. Android may delay execution, so describe eligibility rather than promise exact-time deletion. This requires coordinator acknowledgment and physical background validation. Avoid overlapping #56 transcription recovery edits and #54 queue work; integrate their final revisions.

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

Confirmed transcript contract: deletion is local, cloud copies and saved insights remain, and passive import/automatic preparation must not recreate the deleted transcript until explicit user Prepare or Restore. The owner answered “Yes, use those semantics”; this decision no longer needs clarification. A dedicated per-episode local cleanup record remains the proposed implementation, with explicit intent separated from passive restore/conflict resolution. Audio-marker interactions remain part of the existing design review, rather than an implied extension of this transcript decision. No marker, schema, automatic-sweep or transcript-deletion code has been written.

## Isolated policy increment evidence

The pure `core/RetentionPolicy.kt` increment has no callers and performs no deletion, persistence, scheduling or setting changes. It requires explicit timestamps and current time as inputs. Defaults are disabled. Eight regression tests cover off/independent settings, never-played audio, earliest audio deadline, exact expiry boundaries, missing/invalid/future origins, overflow, policy changes and redownload age. Protection/retry/migration/UI tests remain unimplemented because those integration stages are held.

TDD red: `/tmp/distillpod-issue60-red.log`, compileTestKotlin fails only because RetentionPolicy does not yet exist. Green: full core suite passed, including all 8 new retention tests; `/tmp/distillpod-issue60-green.log`. Repository data-guard regression tests (4), secret-rule regression check and git diff --check also passed. No Android APK, emulator, physical-device or CI check has run for #60. This pure increment does not exercise those paths; all remain required before integrated feature completion.
