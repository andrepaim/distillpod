DistillPod 0.7.0-alpha.1 makes Google Drive backup a one-time setup followed by one-tap saves and an in-app restore history.

- Choose a Drive backup file once using the suggested filename. DistillPod remembers it across launches.
- Back up now saves to that file without opening another save dialog.
- Restore a backup lists dated snapshots inside the app, newest first. Up to ten recent snapshots are kept within the history size limits.
- Reconnect an existing managed backup file once after reinstalling or on another device.
- Import older JSON backups with an unrestricted file-type picker, including providers that misclassify JSON.
- Verified local recovery copies protect against unavailable providers and detected interrupted writes. Existing standalone exports are kept.
- Safe merge, optional portable preferences and credential/audio/model exclusions remain in place.

Backups are manual, and the provider manages cloud sync. They contain readable personal library data; ZIP compression is not encryption. The new flow is validated with a synthetic document provider on an emulator. Physical Drive testing of this version's remembered-file flow remains outstanding when the test phone is unavailable.

Install over the compatible personal-alpha app; do not uninstall as an update step. Android 10+; ARM64 and x86_64. See docs/android/drive-backup.md for usage, retention, recovery and limitations.
