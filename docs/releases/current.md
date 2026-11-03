DistillPod 0.6.0-alpha.1 adds manual Google Drive backup and restore.

- Open Settings → Backup & Restore to save through Android’s file picker to Drive, another provider or your device.
- Snapshots are prepared locally before choosing a destination. Each save creates a new timestamped file and reads it back to verify its bytes.
- See the last saved file, date, size and library counts. Your file provider manages cloud sync.
- Import previews date, source version, counts and existing-record conflicts before merging. Existing items and newer local progress are kept.
- Restore language/model choices optionally. Account credentials, audio downloads, model files and device constraints are excluded.
- Older backup files remain importable. Failed transfers, corrupt files and future formats receive clear recovery messages.

Backups are readable JSON containing personal library text and feed addresses. Keep them private. This feature is manual; scheduled backups and passphrase encryption are future work. No new Google sign-in is required in DistillPod.

Install the APK over the compatible personal-alpha app; do not uninstall as an update step. Android 10+; ARM64 and x86_64 included. The application ID and retained signing certificate are unchanged.

This remains a personal alpha. AI features depend on eligible account/model access and plan limits. Web research, broader device/accessibility, Bluetooth/phone-interruption and sustained battery trials remain open. See the backup guide in docs/android/drive-backup.md for usage and recovery.
