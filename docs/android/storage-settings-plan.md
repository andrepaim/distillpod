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
6. Before merge: current-main integration, CI, isolated emulator checks and fresh non-destructive USB-phone validation of storage navigation/reporting/confirmation. Never execute cleanup on owner data during acceptance.

## Device checks still required
On a disposable emulator seed completed, paused, orphan and protected synthetic files; verify reported totals, cleanup confirmation/cancel, protection errors, refresh after cleanup and download retry. On the owner's compatible development installation verify navigation, reporting and opening/canceling confirmation only. Preserve all accounts, downloads, models, queue and listening state. Coordinate device ownership before use.

## Validation evidence (2026-11-08)
- TDD red: the new storage tests failed compilation for the intentionally missing storageUsage/discardPausedPartial APIs before implementation.
- Green: StorageUsageTest (7 tests) and existing ProductionDataTest (6 tests), 13 passed with no skips/failures.
- Review removed an overly conservative last-player-session deletion guard. Confirmed removal preserves the saved session and listening position when playback is inactive; live playback, queue and processing protections remain.
- Added StorageSettingsDeviceTest to ordinary isolated emulator discovery, including 1.6x font scale, actual totals, model navigation, cancel/confirm and updated counts. It creates unique synthetic media/model files and refuses physical phones.
- Android test compilation, lint and remote CI remain pending at initial PR creation.
- No local emulator or physical phone was used. A fresh non-destructive owner-phone check remains mandatory before merge. No owner data was inspected or removed.
