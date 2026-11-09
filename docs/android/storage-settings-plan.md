# Storage settings consistency (#24)

## Behavior and scope
Read physical file sizes from the app-owned media directory for both audio quota enforcement and Settings. Break out tracked completed audio from partial/cache bytes; show model-directory space separately. Lowering the quota never evicts files: existing audio remains playable and further downloads need room.

Provide an expandable Manage downloads section with per-episode navigation, confirmed removal of completed audio, and confirmed discard of a selected paused partial. Saved text and listening history remain intact. Recheck playback, queue and transcription protections when an action executes, not only when its button is shown. Serialize discard with audio transfers and reset resume validators after successful removal. Keep the existing Clear unused audio explanation narrow and accurate.

No database migration, backend, signing, release metadata or account changes. Storage is an on-demand snapshot refreshed when download/model state changes and after cleanup.

## Steps and acceptance
1. Write synthetic Room/Robolectric regressions for physical byte reporting, partial-only and orphan usage, lowered limit, protected files, and discard/retry.
2. Share the media file-accounting helper between quota enforcement and usage reporting.
3. Implement guarded partial discard and harden the existing individual removal route.
4. Extract the storage panel without changing Settings navigation owned by #22.
5. Run bounded unit checks, review combined changes, open a draft PR.
6. Before merge: current-main integration, CI and isolated emulator checks under the risk assessment below. No owner-data cleanup trials. Preserve the independent physical permission-acceptance requirement inherited from #20.

## Acceptance scenarios
On a disposable emulator seed completed, paused, orphan and protected synthetic files; verify reported totals, cleanup confirmation/cancel, protection errors, refresh after cleanup and download retry. If a separately required physical check is coordinated, inspect navigation/reporting and cancel confirmation only. Preserve all accounts, downloads, models, queue and listening state. The storage-specific assessment below does not require such a device trial.

## Validation evidence (2026-11-08)
- TDD red: the new storage tests failed compilation for the intentionally missing storageUsage/discardPausedPartial APIs before implementation.
- Green: StorageUsageTest (7 tests) and existing ProductionDataTest (6 tests), 13 passed with no skips/failures.
- Review removed an overly conservative last-player-session deletion guard. Confirmed removal preserves the saved session and listening position when playback is inactive; live playback, queue and processing protections remain.
- Added StorageSettingsDeviceTest to ordinary isolated emulator discovery, including 1.6x font scale, actual totals, model navigation, cancel/confirm and updated counts. It creates unique synthetic media/model files and refuses physical phones.
- Android test compilation, lint and remote CI remain pending at initial PR creation.
- No local emulator or physical phone was used. At initial PR creation the then-effective policy required a fresh owner-phone check; the subsequent risk-based reassessment below supersedes that blanket gate. No owner data was inspected or removed.

## Risk-based reassessment after Settings integration (2026-11-09)
Policy PR #50 is effective. This change has moderate local-data integrity risk,
not merely a cosmetic UI risk, but introduces no hardware/OEM-specific API or
upgrade/schema/signing change. It uses app-private filesDir, Java File logical
length/deletion, existing transfer locks and Room. Model files remain separate
and audio cleanup cannot delete them. Actual file-size reporting and quota use
the same enumeration, including partial/orphan files; lowering limits never
evicts content.

The selected actions require confirmation, canonical path containment, current
paused/queued/downloading state checks, active playback/processing checks and
database queue/transcription protection. Saved text, listening progress and
inactive player-session state are preserved. Synthetic regression tests also
cover deletion failure retaining resume metadata and path traversal refusal.

Automated and disposable-emulator validation are appropriate for these changed
behaviors; an owner-phone deletion trial is neither needed nor authorized.
No new storage-specific physical check is required. This assessment does not
waive #20's independent notification/system-Settings physical acceptance gate.
#22's UI organization requires combined emulator navigation, not an additional
blanket physical gate.

Residual limitations: filesystem deletion and Room commit are not one atomic
transaction; process death between them is not fault-injected. Every concurrent
playback-start interleaving is not exhaustively tested. Failed filesystem
deletion is tested with a non-empty synthetic directory, not OEM I/O faults.
Usage is a refreshable logical-byte snapshot, not physical allocated-block
usage. These are explicit limits, not proof of a discovered regression.

Integration preserves #22's canonical NotificationControls/NotificationAccess,
charging control in transcription, refresh controls in library, and #24's
extracted StorageSettings panel. #23 changes backup/sync callbacks elsewhere
in ProductApp, with no semantic storage dependency. Re-run combined navigation,
storage, notification and transcription checks plus exact-head CI after stacking.
