# Sync as the single data-protection concept

Settings used to offer two neighbouring rows, **Backup & Restore** and **Library sync**, with a card on each
page explaining that they "protect different things". Owners found the pair confusing. They were not
redundant, though: sync (Firestore) carries subscriptions, progress, completion, saved moments and,
optionally, queue and three preferences. Transcripts, analyses, chats, research and posts exist only on the
phone and in backup files. Sync also has no history to roll back to, and builds without Firebase have no
sync at all. Deleting backup would have left the most expensive data with a single copy.

This change keeps both mechanisms but shows one concept: **Sync**. Backup becomes a daily, automatic part of
it instead of a manual feature the owner has to understand and remember.

## What the owner sees

- **Settings home:** one **Sync** row ("Google account, automatic backup and restore") replaces both rows.
  Its value shows "Needs attention" when the last backup failed. Otherwise it shows the sync state when an
  account is bound, and the backup state otherwise ("Backed up 3 h ago", "Off").
- **Settings › Sync** (`settings:sync`):
  1. One summary line with **Learn more** ("What's covered"), which holds the full sync scope, backup scope,
     exclusions, new-phone steps and account binding text (#203 style).
  2. **Cloud sync:** the existing account, approval, scope, conflict and rejected-change controls,
     unchanged. A build without Firebase leaves this section out; the summary line says sync is unavailable.
  3. **Automatic backup:** without a file, a short reason and **Set up automatic backup**, which opens the
     existing create-document picker. With a file: status ("Backed up 2 h ago" or "Last backup failed" and
     the reason), the file name and **Back up now**.
  4. **Restore from a backup** opens the `backup` route.
- **Backups & restore** (`backup`, reached from Sync): the existing managed-history screen. It drops the
  "Manual only" copy and the cross-link card. "Forget backup connection" becomes **Turn off automatic backup**,
  which keeps the same behavior: the file and its history stay.
- **Library page:** the two links become one **Sync & backups** button.

Route strings are unchanged (`settings:sync`, `backup`), so deep links and the route contract in
[settings navigation](../settings-navigation.md) still hold.

## Automatic backup

`ManagedBackups` moved from `AppViewModel` to `AppGraph` so the screens and a worker share one instance,
one progress state and the existing process-wide operation lock. Restore previews now reach the open
screen through `ManagedBackups.previews` instead of a constructor callback into the ViewModel.

`BackupWorker` runs `ManagedBackups.automatic()` once a day with network connected and battery not low. It is
enqueued (`managed-backup`, KEEP) when a file is created or connected and at startup when one is connected,
and cancelled when the file is forgotten. It runs in every build type.

`automatic()` performs the same verified write as **Back up now**: it reads and validates the existing
archive, keeps a recovery copy, checks that the file did not change, writes, and reads the bytes back. The
differences:

- **Unchanged libraries are skipped.** `LibraryBackup.contentFingerprint` hashes the snapshot without its
  `createdAt` and `appVersion`, including post images. If it matches the newest snapshot, nothing is written.
  A repaired (recovered) archive is never skipped. Manual **Back up now** always writes.
- **Busy:** if a manual action or file picker is in progress, the worker retries later.
- **Failures** are shown in the Sync page and the Settings row. Lost access, a missing file or an unsupported
  newer archive wait for the owner. Other failures retry twice with backoff, then wait for the next day.

The outcome (`checkedAt`, `savedAt`, `error`) is stored in the `managedBackupStatus` preference, so Settings
shows backup age without reading the provider file. Manual backups and connecting a file update it too.

### Retention

The history format still holds at most ten snapshots. Before, the oldest snapshot was dropped, which with
daily backups would have meant only the last ten days. `BackupArchive.retain` now keeps the five newest and
thins older snapshots from the most crowded calendar week, dropping that week's oldest. Daily backups keep
five days plus roughly one per earlier week, reaching back about a month. Many saves on one day still drop
the oldest first. The archive format and reader are unchanged, so older app versions read these files.

## Not changed

Sync protocol, Firestore scope, account binding, archive and snapshot formats, restore preview and merge
rules, standalone JSON import/export, OPML and privacy exclusions.

## Validation

JVM tests (`ManagedBackupsTest`, `BackupRetentionTest`, `BackupArchiveTest`, `DataProtectionGuidanceTest`,
`SettingsOverviewTest`) cover: no file connected; skip when unchanged and save after a change; failure stored
and cleared; lost access needing the user; busy while a picker is open; schedule following
connect/disconnect; previews reaching the screen; status parsing; Settings row value precedence; retention
over sixty simulated days; and worker retry policy.

Device tests updated for the new copy and navigation: `DataProtectionDeviceTest` (Sync → Restore → Back),
`DesignReviewDeviceTest` (Settings at 160% and 200% font scale), `SubscriptionSyncUiDeviceTest` and
`ManagedBackupsDeviceTest`. Run them in isolated emulator acceptance.

### Physical device, 2026-11-17

Galaxy S22 (Android 15), side-by-side debug build `dev.distillpod.android.sharecards`, no Firebase. Backup file
in the phone's Downloads through the system picker (local storage provider, not Drive):

- Settings shows one Sync row; the Sync page shows the unavailable-sync line, the backup card and the restore
  link. Setup created `DistillPod-backups.zip` and registered the daily job (battery not low, network, 30-minute
  backoff).
- The first periodic run starts immediately on enqueue. It ran right after setup and skipped the unchanged
  library (file hash unchanged). After a preference change and reconnecting the file, it wrote a second
  snapshot (manifest checked: two dated snapshots).
- `cmd jobscheduler run -f -n androidx.work.systemjobscheduler <pkg> <id>` cold-started the killed process, but
  WorkManager declines a periodic run before its period ("not doing any work and rescheduling"). Forcing it
  is therefore not a way to test a later daily run.
- With the file moved away, Back up now showed "Last backup failed" with the reason and a reconnect link;
  the Settings row showed "Needs attention". After moving it back, the error cleared ("Backed up just now").
- Found and fixed on device: the card showed "On" instead of "Backed up just now" right after a save, because
  its clock predated the save. The restore screen had the same stale clock, pre-existing.

## Risk assessment

This change adds **background work that writes to a user-selected provider document**, which the per-change
policy treats as platform-sensitive. Emulator and JVM coverage exercise the logic with a synthetic
DocumentsProvider. They do not prove that Google Drive's provider accepts a write from a background worker
with the app closed, or how Doze delays the daily run. Before release, validate on a physical phone with a
side-by-side debug build and a throwaway Drive file, not the owner's library or backup file:

1. Set up automatic backup to Drive, close the app, and force the job
   (`adb shell cmd jobscheduler run -f <package> <job id>` or wait a day). Confirm a new snapshot in Drive
   when the library changed and none when it did not.
2. Revoke access (delete or move the file in Drive) and confirm **Needs attention** in Settings with the
   reason on the Sync page, and that no retry loop runs.
3. Toggle airplane mode during a run and confirm the archive is intact and a retry follows.

Out of scope for now: a notification for repeated failures, end-to-end encryption of backup files, and
syncing transcripts through Firebase (blocked by the billing-disabled project; see [privacy](../privacy.md)).
