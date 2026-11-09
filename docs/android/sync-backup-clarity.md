# Sync and file backup clarity (#23)

This change explains existing behavior. It does not enable sync, change account
binding, expand the portable preference set, alter the backup format or change
restore preview/merge rules.

## Copy sources

- `SubscriptionSyncAccount.contentConfigured` checks the content flag, configured
  sync and storage bucket; the shared explanation also checks expanded scope.
- `CollectionRecord.preferences` and `PortablePreferences` allow only local model
  selection, transcript language and AI model selection.
- `FinishedContent.snapshot` requires an eligible public source and a ready
  transcript matching its media fingerprint. It includes eligible completed
  analysis, chat and research, not every library record.
- `enableFullScope` upgrades the account protocol. There is no independent
  downgrade control. Pause/sign-out stop this installation; they do not delete
  remote records or remove the account binding.
- `ManagedBackups` remembers a document selected through Android's picker. Its
  read-back integrity check does not establish that a provider uploaded the file.
- `LibraryBackup` and `BackupDocuments` retain preview and merge safeguards.

Backup age uses the greatest timestamp in loaded history, not the last successful cloud upload. Archive reads sort newest first, but saves prepend the new snapshot, so the display does not assume list order after clock changes.
A fallback recovery copy is explicitly labeled. Missing/invalid/future dates are
not presented as a fresh backup. While the screen is open, age updates every minute.

## Acceptance checks

Automated JVM coverage exercises all configured/content/expanded-scope combinations
and missing, invalid, recent, minute/hour/day and future backup timestamps.
Existing archive and document tests cover retained restore safeguards.

`DataProtectionDeviceTest` navigates sync -> backup -> sync -> Back without
connecting accounts or changing library data. Run on an isolated emulator, with
the existing settings large-text test. Also inspect:

1. Default sync-disabled build: no implication that data is currently syncing.
2. Configured metadata-only build: no claim that finished text syncs.
3. Synthetic content-enabled build before/after expanded scope: wording matches.
4. Backup with no history, old history and local recovery fallback: manual
   scheduling, age and provider-upload limitation appear next to the action.
5. Picker cancellation and restore-preview dismissal leave library data intact.
6. A new-phone reader can identify the original sync account, separately selected
   provider/file, three portable preferences, excluded local settings and text
   restoration path.

## Per-change risk assessment

Under the risk-based policy merged in PR #50, this change has low hardware/platform
risk: explanatory copy, ordinary in-app cross-links and display-only backup age
selection. Sync/restore logic, account permissions, file-picker mechanisms,
archive formats, persisted settings, background work and upgrade/signing behavior
are unchanged. Incorrect scope wording could mislead recovery expectations, so
copy was checked against the configured flags and actual serializers, including
provider-upload limitations.

Focused JVM checks cover flag combinations, age edge cases and existing backup
safeguards. Compose navigation, picker/history/restore and scope tests run in
ordinary isolated Android acceptance. Together with source review, lint and
current-main CI these are suitable evidence for this UI-only scope; physical
hardware is not needed to establish the changed behavior.

Exact-head Android CI 42536528104 and repository security passed on 5daf899,
including isolated Android acceptance. Initial CI failures were corrected by
scrolling setup controls into view and replacing obsolete copy expectations,
without removing picker/restore assertions. After integrating policy and the
already-merged pause prerequisite, rerun relevant checks and CI on the combined
head. Earlier results do not validate later integration automatically.

No physical-phone or live-account validation is claimed. This does not waive
physical evidence for permission recovery, OEM behavior, backups/restores or
other platform mechanisms if a later change affects them. Reassess integration
with settings organization (#22), preserving its canonical notification route
and this change's exact sync/backup guidance.
