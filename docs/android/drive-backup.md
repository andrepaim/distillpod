# Google Drive backup and restore

## One-time setup

Open **Settings → Backup & Restore → Create backup file**. In Android’s picker choose the intended **Drive account → My Drive**, select a folder, and save the suggested **DistillPod-backups.zip** name. DistillPod remembers read/write access to that exact document. After setup:

- **Back up now** saves to the connected file without choosing a folder or typing a filename.
- **Restore a backup** lists dated snapshots inside DistillPod, newest first. Select one, review its contents, then merge.
- **Connect existing backup** reconnects a managed backup file after reinstalling or on another phone. This requires a file selection once on that installation.
- **Connect another backup file** changes the destination. **Create another backup file** starts a separate history while keeping the old file.
- **Forget backup connection** removes the remembered connection and releases its persistent file permission. It does not delete the cloud file or library.

The connected document keeps up to ten recent snapshots, subject to a 128 MiB total expanded-history limit and a 64 MiB archive limit. Each individual snapshot keeps the existing 32 MiB/100,000-record library limits. Older snapshots are removed from this managed history to fit the count and expanded-size limits. If the compressed archive still exceeds its limit, create another backup file; the existing file is kept. Standalone JSON files exported by older versions are never automatically converted, overwritten or deleted.

This is **one-tap saving to a remembered destination**, not a scheduled background backup or live synchronization between libraries. Your file provider manages upload and cloud sync. A successful read-back confirms the provider accepted the saved bytes, not that another device has already downloaded them. Check Drive before relying on the file from another device.

## Restoring and older exports

The in-app history shows date, source app version and library counts. Selecting a snapshot validates its library format and integrity, then shows a second preview with local-record conflicts. **Merge library** keeps every existing local record and listening position, and inserts missing records in one Room transaction. Invalid relationships roll the transaction back. Repeated imports do not duplicate records. Restoring an older snapshot does not rewind existing local progress.

Optional language/model restoration remains off until selected. Imported data never starts playback, downloads or AI work. Download models/audio and reconnect ChatGPT separately on another device.

Use **Import older backup** for a standalone JSON export. This picker now shows **all file types** because a cloud provider can classify a JSON file differently. The app validates the actual contents before offering a restore. If Drive looks empty, verify the selected Google account and enter **My Drive** rather than staying at its top-level locations view. **Export a standalone JSON copy** remains available as a portable manual transfer.

## Recovery and consistency

Before replacing an existing connected archive, DistillPod reads and validates it, builds the next archive locally, and saves a verified recovery copy in private Android storage. It checks whether the destination changed before writing, closes the write, and reads the saved bytes back for comparison. A detected incomplete update attempts to restore the previous valid archive, while preserving an independently updated valid archive.

After a successful save/read, an atomic local replacement retains the most recently verified archive as a recovery copy. If Drive cannot be read, the history can offer this copy with an explicit **Recovery copy on this phone** label. A damaged remote archive can be rebuilt from that verified copy when the user next chooses **Back up now**. Unsupported future archive versions are never overwritten with an older recovery copy. File-provider writes do not offer an atomic cross-device compare-and-swap guarantee; this feature is backup history rather than multi-device synchronization. A power loss can require the recovery copy from the original phone. Do not depend on an unfinished save as your only backup.

Newly created files are written before read-back, avoiding providers that cannot read a newly created empty cloud document. If connecting a replacement file fails, an existing connection is retained where settings storage remains available. Successful file writes are not rolled back merely because recording local connection details fails. Temporary working files are removed after each operation; Android may also reclaim app cache. Local recovery copies are private, excluded from Android cloud/device-transfer backup, and do not replace the need for a verified cloud copy.

Room library merge and optional preference storage are separate transactions. Preference failure reports that the library was restored and explains how to retry preferences. Queue refresh failure gives a reopen instruction. Neither is misreported as a library rollback. When library sync is connected, restored progress, completion and saved moments of feeds already shared with sync are queued in the merge transaction and upload like local edits; see [library sync](subscription-sync.md#backup-restore).

## Data and formats

Included: subscriptions and episode metadata, listening position/completion, queue and saved player speed, transcripts/coverage, saved moments, analyses and parts, stored chat/research/suggestions. Portable preferences are explicitly allowlisted: transcription model choice, language and AI model choice.

Excluded: downloaded audio, private audio paths, model binaries, account tokens, device network/power/storage settings, job requests/states, recent searches, connected-document URIs and backup receipts. Preferred models can be unavailable on the destination; install/select an available model before using them.

Managed histories are ZIP files containing a small manifest and ordinary versioned DistillPod JSON snapshots. ZIP compression is **not encryption**. The contents include personal text and feed URLs, which may include private access links. Keep the file private. Checksums detect corruption, not authorship. Passphrase encryption and scheduled backups remain future work.

History format 1 contains bounded entries with independent SHA-256 checksums. Import checks entry names, duplicates, expanded sizes, record metadata and hashes, and never extracts user-supplied paths. Every selected snapshot still passes the existing library validation and atomic-merge rules. Standalone library formats 1 and 2 with backup schema markers 1–4 are supported. The backup schema marker is separate from the Room database schema (currently 8). Older backups receive defaults for fields introduced later, including online transcripts; lookup consent and retry cooldowns are not restored.

## Architecture and validation

The implementation uses Android Storage Access Framework document creation/opening and user-granted persistent access. It does not require broad storage permission, Drive OAuth, the Drive REST API or a DistillPod server. Folder-tree access is not assumed: the user grants one file, so providers that cannot grant Drive folder trees can still be used.

JVM/provider tests cover remembered permissions, repeated snapshots without a destination picker, history after coordinator recreation, historical restore, corrupted history, unsafe ZIP entries, bounded retention, revoked access, verified local recovery, interrupted writes/rollback, connection cancellation and safe disconnection. Emulator tests use a synthetic DocumentsProvider to exercise real system pickers, persistent grants, default naming, a second save without a picker, fresh-target reconnection/restoration, and legacy JSON mislabeled with an unexpected MIME type. Physical Drive testing of the new remembered-file flow remains outstanding when no phone is connected.

- [Android: access documents and other files](https://developer.android.com/training/data-storage/shared/documents-files)
- [Google Drive API terms](https://developers.google.com/workspace/drive/api/terms): direct API app/user-content backup requires prior written consent. This implementation uses a user-selected document through Android’s picker.
