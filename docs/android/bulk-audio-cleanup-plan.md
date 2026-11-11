# #60 next increment: confirmed bulk managed-audio cleanup

This is planning only until PR114's merged-main CI succeeds. Base will be refreshed
origin/main including0bc7eb3. Root owns issue60, one feature branch/worktree and one
coordinated integration lane. Parent60 remains open after audio controls for local
transcript suppression, separate transcript controls and opt-in retention/scheduler.
No release, schema, account/backend, model removal or automatic cleanup is added.

## Behavior and fixed decisions

Storage settings gains Review audio to delete / Clear all local audio. Explain
that completed downloads, unfinished downloads and other managed audio cache are
reviewed; models, transcripts, saved outputs, subscriptions and progress remain.
Show actual byte estimate, distinct physical-file counts by those three kinds,
episode titles for known content, and protected/unavailable counts. Cached orphan
files use a user-facing aggregate label, never internal UUID/path text.

Review writes no content, row or preference. Keep a fixed opaque in-memory ticket
bound by identity to this MediaRepository; do not persist executable targets in
SavedState/work data/backups. A new process/repository requires a new review.
Consume the ticket on apply entry. Duplicate confirm cannot start another batch;
cancel before any deletion still requires a fresh ticket. Preview cancellation or
late completion cannot reopen a dismissed or newer dialog.

Snapshot on IO without awaiting the global transfer mutex. Register generation
before stat. Capture explicit presence/type for each relevant path, including
ABSENT companion partials. Completed copy identity includes its UUID path and
stable source/local-age/fingerprint fields, not the whole episode: title/progress
changes alone must not invalidate it. Capture unfinished copy identity from the
registry; size/mtime/ETag equality is not generation identity. Enumerate orphan
membership once. Never rescan-and-delete files newly arriving after confirmation.

Deduplicate canonical physical files, retaining original stored URI/row identity.
Build connected cleanup units over episode references and completed/partial paths
so shared copies are deleted/counted once and ALL associated rows/protection are
checked. Capture every associated partial, even absent; do not clear a secondary
row's resume metadata while preserving its unreviewed partial. Groups protected
or unstable at review stay outside this ticket's executable set even if later
idle. Newly protected executable units are safely skipped at apply.

All ordinary managed direct files can be considered, including paused/failed
partials and orphan audio. Models and outside-managed diagnostic clips are out
of scope. Invalid canonical containment/non-regular entries are kept/error items;
never recursively remove directories or follow a symlink outside media.
Missing completed files count as zero removed bytes/files; still-matching download
status may be reconciled explicitly, with unavailable-entry counts explained.

## Ownership and deletion protocol

Extend AudioFileOwnership with atomic reserveExpected of the exact Selection
objects, returning Success / Stale / Busy. Validate the complete request before
revoking any generation, including absent companion paths. Keep IO outside its
monitor and retain all current queue/reader/transcription guards.

Reserve each download partial before any reset/write/append/truncate/delete/rename
on the non-fast-path download attempt. Hold the marker through actual downloader
writer completion, validation and failure cleanup; close in finally. Preserve the
existing downloader cancellation/join, range, validator and retry behavior. A
reservation marks ownership without holding the monitor through network IO.
Selection during this mutable interval is unstable/protected. Conservative stale
classification after an attempt must not be called proof that bytes changed.

Factor a private guarded cleanup helper, not recursive calls to non-reentrant
public deleteDownload. Keep transfer -> episode locks -> Room transaction order.
Under those guards, re-read ALL current references/queue/transcript/download state,
match the captured stable identities and expected path presence/type, and reserve
only the expected generations. Reclassify orphan adoption as changed/kept.
No replacement or new partial can become an implicit target. Keep the existing
whole-copy protection priorities, not just a per-primary-id advisory check.

Delete reviewed partials before completed files. Only matching representations
receive download/validator reset; preserve transcript and listening/saved state.
Independent units continue after expected protection/staleness or ordinary item
failure. Do not catch CancellationException as an item error. Finish only the
short already-started destructive unit non-cancellably; mutex/network wait and
remaining batch stay cancellable. A confirmed operation may wait for the current
transfer: explain this and allow cancelling cleanup without cancelling a download.

## Result and cancellation accounting

Count distinct physical files and actual bytes removed, split completed / unfinished
/ other cache. Absent companions are guards, not removed items. Count protected,
changed, unavailable/error and failed units explicitly; an eligible shared unit
may cover multiple episode labels but its physical file is counted once.

File unlink and SQLite commit are not atomic. Preserve partial success if one file
is removed before another removal or metadata commit fails. Never claim a rollback
restored files. Keep original unexpected errors for bounded reporting while UI
gets sanitized counts. Do not clear a replacement during error reconciliation.
A fresh review must handle missing-file status and allow explicit retry after a
fault; no blind retry against newer current content.

Keep an immutable latest progress/result on the ticket/controller so cancellation
after one unit still reports that unit's removed files/bytes. UI must refresh
actual storage usage on success, partial failure and cancellation. Keep physical
file counts distinct from download-status entries reconciled without a file.

Use a ViewModel-owned controller for preview/apply jobs and state. Storage UI
renders that state; no saved executable ticket. Closing a preview invalidates its
request generation and cancels its work. Confirm marks Running before launching,
preventing duplicate clicks. Explicit cancel requests stop between units; retain
latest partial result. ViewModel teardown cancels its jobs safely. Screen/navigation
must not revive late preview state, and storage refresh runs outside a cancelled job.

## TDD and acceptance

1. Registry tests: expected-generation success/stale/busy, batch all-or-none,
   absent path, nested reservation and irreversible old-token invalidation.
2. Real Room/files: review/cancel are side-effect free; clear eligible completed,
   paused/failed partial and orphan copies; preserve all unrelated records/models.
3. Replacement after review, new partial at absent companion, same-size/validator
   reset attempt, writer failure-before-bytes, held writer and cancellation unwind;
   actual ResumableDownloader/MockWebServer test proves production wrapping.
4. Shared aliases/rows, queued secondary reference, active reader/transcription,
   orphan adoption, newly arriving orphan, missing files and invalid containment.
5. Controlled IO and real Room-trigger failures: partial success/accurate bytes,
   no fictitious file rollback or replacement metadata clearing; fresh-ticket retry.
6. Cancellation before lock and between units; duplicate/cross-repository/reused
   tickets; progress-only row changes; result units do not count absent guards.
7. Controller tests: late preview after dismiss/new request, duplicate confirm,
   cancellation retaining partial counts, failures and teardown. Actual Compose
   confirmation/cancel/count refresh and protected/stale feedback.

Run meaningful red tests before corresponding behavior, then focused green,
review, both full default/voice gates, Python/data/secret guards and exact-head
hosted checks. Test real queue/reader/downloader integration on the owned emulator.
Use a fresh separately identified immutable offline validation pair on the S22,
with synthetic files/Room/actual Media3 and UI. Offline physical writer overlap
can exercise the same scoped writer helper with a real FileOutputStream; do not
require network permission or live services. Verify hashes/IDs/signer/target and
permissions, remove only verified own packages, preserve production app/data.

Risk: high data-integrity impact and a partial-writer lifetime synchronization
change. Physical Android16 file/reader/UI evidence is required. It does not prove
all OEM scheduling, arbitrary external file mutation or filesystem/DB atomicity.
No production upgrade/schema change is intended. Reassess if scope changes.

After acceptance, refresh/integrate current main, validate actual combined head,
merge normally and require successful merged-main CI. Do not close parent60 yet.

## Audit decision: canonical processing lease

Actual production audio processing enters withDownloadedAudio; the other direct
ActiveFiles.acquire calls protect model files, which this action excludes. Legacy
processing counters use raw paths. Retain those counters/notifications, and add a
canonical registry lease to withDownloadedAudio under the existing transfer lock
before exposing the file. Compute canonical identity on IO, hold the lease through
consumer unwinding and release it in finally alongside the original raw counter.
This prevents an alias plus detached episode row from making an active audio
reader look like an unowned orphan. Do not canonicalize the caller's source URI.
Add a controlled failing alias/detached-orphan regression before this change,
retaining the existing raw ownership, cancellation and exception behavior tests.
This is part of the audio-deletion safety work, not a claimed production incident.

## Implementation ownership / API contract

After mainCI succeeds, one root-owned bulk branch/worktree contains this feature.
A backend worker owns only data/AudioCleanup.kt, MediaRepository.kt,
AudioFileOwnership.kt and their new/focused tests. Root owns StorageSettings,
AppViewModel and the cleanup controller/UI tests and final integration. File
ownership is disjoint; no concurrent edits to backend files or concurrent builds.
Only root schedules emulator/phone/merge use. Delegation is within one issue.

Expose internal suspend previewAudioCleanup(): AudioCleanupPreview and
clearAudio(preview, onProgress = {}): AudioCleanupResult. The opaque preview exposes
only immutable counts and display rows plus canApply; internal units/owner/ticket
consumption stay outside UI. Apply publishes immutable running/result snapshots
after units and before a cancellation exit; callback is a fast state update, not
IO. Controller retains latest progress if cancellation propagates. Unexpected
item failures are retained for the existing bounded unexpected-error reporting;
protected/changed outcomes never become unexpected error reports.

Use downloaded/unfinished/cached physical-file and byte counts consistently.
Result includes actual removed counts, protected/changed/failed counts, missing
status reconciliations and a terminal/cancelled flag. Display rows label known
episodes and file kind/status; paths and generation identifiers are internal.
Root/backend agree exact Kotlin field names before parallel UI implementation.

## Existing automation interaction

An enabled, initialized subscribed show's non-ready EpisodeAutomation job can
restart missing audio after deletion because bulk resets downloadState to none.
Keep all copies belonging to such unfinished preparation jobs, in preview and
at the final transaction, including a newly introduced secondary reference.
Explain this protection as automatic preparation; do not disable automation,
change preferences, cancel its jobs or invent an audio-suppression policy.
Ready jobs remain eligible: the processor excludes ready jobs. Test newly added
unfinished intent after review, ready-job eligibility, disabled/unsubscribed
intent, and unchanged job rows/preferences.

Acquire captured episode locks in sorted order before entering Room. A reference
outside that captured/locked set discovered in the transaction makes the unit
stale; never acquire a new episode mutex from inside the Room transaction.
