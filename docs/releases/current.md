DistillPod 0.33.0-alpha.1 puts everything that keeps your library safe in one place, **Settings → Sync**, and backs your library up automatically every day.

- **One Sync page instead of two.** Settings no longer has separate **Backup & Restore** and **Library sync** rows. The single **Sync** row opens a page with your Google sync account (unchanged), an **Automatic backup** card and **Restore from a backup**. The row shows the current state: sync status, "Backed up 3 h ago", "Off", or **Needs attention** when the last backup failed. The Library page links to it with one **Sync & backups** button.
- **Backups now run by themselves.** Choose a backup file once (Google Drive, the phone, or another place in Android's file picker) with **Set up automatic backup**. DistillPod then adds a dated backup to that file once a day, when your library has changed and the phone is online with enough battery. If nothing changed, nothing is written. **Back up now** still saves immediately.
  - Sync doesn't carry transcripts, insights, chats, research or posts, so the backup is what protects them.
  - If a backup fails, the Sync card says why and offers **Reconnect or change the backup file**. Lost access waits for you; other problems are retried a couple of times and then tried again the next day.
- **Longer backup history.** The file still holds up to ten backups. Instead of the last ten saves, it now keeps the five newest plus one from each earlier week, which reaches back about a month with daily backups.
- **Restore lives under Sync.** **Restore from a backup** opens the backup history screen, now called **Backups & restore**. Restoring works as before: pick a dated backup, review it, then merge. **Forget backup connection** is now called **Turn off automatic backup**; it keeps the file and its backups.
- **Fixed:** right after saving, the backup screen could describe the new backup as "ahead of this phone's clock".

Install the signed APK over the existing app; do not uninstall first. This personal alpha keeps application ID `dev.distillpod.android` and the existing signing identity, and raises the version code to **57**. Database schema, backup file format and Firebase rules/protocol are unchanged from 0.32.0-alpha.1, so older versions can still restore the new backup files. **If you had already connected a backup file**, it starts receiving daily automatic backups after this update; use **Turn off automatic backup** if you don't want that. Downgrading the APK is not supported.

**Known limits:**

- **Google Drive not yet tried on a phone.** Automatic backup to a file in the phone's Downloads folder was tested on a Galaxy S22. Writing to a Drive file from the background, with the app closed, has not been checked yet.
- **A real overnight run is still pending.** Android only runs the daily backup when its day is up, so the first scheduled run after setup was checked, but a later run under battery-saving idle has not been observed yet.
- **The cloud-sync part of the new page** was checked with the automated tests and emulator, which have no Firebase account, not with this signed build on a phone.
- **No notification for repeated failures.** A failing backup shows on the Sync page and the Settings row only.
- **Still open from earlier releases:** the limits listed in the [0.32.0-alpha.1](https://github.com/andrepaim/distillpod/releases/tag/v0.32.0-alpha.1) notes and earlier.

**Validation:**

- **Hosted CI:** [#270](https://github.com/andrepaim/distillpod/pull/270) passed full CI, including builds, lint, Firebase rules, native checks and isolated API 35 acceptance with the updated Settings, Sync and backup device tests.
- **New tests:** automatic backup with no file connected, an unchanged library skipped, a changed library saved, failures stored and cleared, lost access waiting for the user, waiting while another backup action runs, the daily schedule following connect and disconnect, restore previews, the Settings row value, history retention over sixty simulated days, and the retry policy.
- **Galaxy S22** (side-by-side debug build, backup file in Downloads): setup, the immediate first background run skipping an unchanged library, a background run saving a second dated backup after a change, the failure message and **Needs attention** when the file went missing, and recovery once it was back.
- **Publication:** the release workflow separately reruns full validation, release lint/assembly and signing. The downloaded assets are then checked for checksums, source identity, package/version, non-debuggable state, retained certificate and alignment.

[View changes since 0.32.0-alpha.1](https://github.com/andrepaim/distillpod/compare/v0.32.0-alpha.1...v0.33.0-alpha.1).
