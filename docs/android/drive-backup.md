# Google Drive backup and restore

## Using it

Open **Settings → Backup & Restore**. Tap **Save backup to Google Drive**, open the location menu in Android’s file picker, choose **Drive**, select a folder and save. You can use another cloud provider or local storage instead. If Drive is absent, install/open Google Drive and sign in, then reopen the picker.

DistillPod prepares a consistent library snapshot locally before asking for a destination. Each save creates a new timestamped JSON document, preserving earlier copies. The app closes the write and reads the file back to check its bytes before recording a successful save. The screen shows the last saved filename, date, size and library counts. This confirms the provider accepted the document; **the provider manages upload and cloud sync**. Check Drive before depending on the backup from another device. Backups are manual: save again after changes.

To restore, choose **Import backup**, open the JSON file from Drive or another location, review the date, source app version, counts and existing-record conflicts, then choose **Merge library**. Existing local records and progress win. Missing records are inserted in one Room transaction; invalid relationships roll that transaction back. Repeating an import does not duplicate records.

Language and model choices can be restored with an optional checkbox, initially off. Model binaries and ChatGPT credentials are excluded. Download models/audio and reconnect ChatGPT separately on another device. Import does not start playback, downloads or AI work.

## Included and excluded data

Included: subscription and episode metadata, listening position and completion, queue and saved player speed, transcripts/coverage, saved moments, analysis and its parts, stored chat, research and suggestions. Portable preferences have an explicit allowlist: transcription model choice, language and AI model choice.

Excluded: downloaded audio, private audio paths, model binaries, account tokens, device network/power/storage constraints, job requests/states, recent searches and backup receipts. A preferred model can be unavailable on the destination phone or account; install/select an available model before using it.

The JSON is **readable, not encrypted**. It includes personal text and feed URLs, which can contain private access links. Keep it in a private location. The checksum detects accidental corruption; it is not proof of authorship. Optional passphrase encryption and scheduled backups remain future work.

## Architecture and recovery

The implementation uses Android Storage Access Framework `CreateDocument` and `OpenDocument` contracts. No broad storage permission, Drive OAuth, Drive REST API, permanent URI grant or mandatory DistillPod server is required. The user explicitly chooses the destination/source on every operation. Android system picker cancellation does not change the library or last successful save.

A bounded app-private temporary snapshot lives under the app cache only until the picker operation finishes. Stale snapshots after process death are removed when the backup coordinator starts again. A result received without its prepared snapshot is rejected and its newly created document is removed where the provider permits. Failed writes/read-back checks attempt to remove their new partial document; providers may refuse cleanup, so remove any incomplete file in that provider before retrying. Earlier backups are never opened for writing.

Room library import and preference storage are separate transactions. Library validation/merge must succeed before optional preferences are applied. If preferences cannot be saved, the screen reports that the library was restored and tells the user to retry the preferences. Queue refresh failure similarly gives a reopen instruction. These failures do not falsely report a library rollback.

Format 2 puts the library, portable preferences, source version and creation date inside the checksummed payload. Format 1/schema 1–2 backups remain importable. Future formats, invalid preferences, checksum mismatches, oversized files and unsupported schemas are rejected. Existing limits remain 32 MiB and 100,000 total records.

## Validation

JVM tests cover version 1 compatibility, version 2 integrity, atomic rollback, identity mapping, existing-record preservation, invalid/future formats, an explicit preference allowlist, fresh-target restoration, repeated import, cancellation, permission loss, provider write failure and truncated read-back. An instrumentation-only document provider exercises the actual Android picker, save/read-back and fresh-database import in the isolated emulator acceptance suite. It contains synthetic data only and is absent from the shipped application APK.

## Platform references

- [Android: access documents and other files](https://developer.android.com/training/data-storage/shared/documents-files)
- [Google Drive API terms](https://developers.google.com/workspace/drive/api/terms): automated app/user-content backup through the Drive API requires prior written consent. This feature is a manual user-directed document export through Android’s picker.
