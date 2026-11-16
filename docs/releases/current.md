DistillPod 0.30.0-alpha.1 keeps transcripts usable while you replace them, improves distilling streamed episodes, and fixes library, backup and sync recovery.

- **Safer transcript controls.** Resume keeps saved progress and its original model/language. Waiting transcripts without progress can use a ready selected model. **Transcribe locally again** asks first and keeps the previous matching transcript readable and searchable until the replacement is ready.
- **Better distill recovery.** Captures from an episode’s own stream or YouTube video no longer require source review simply because playback is remote. An incomplete passage offers **Include what comes next** and **Include earlier context**. Expanding without finding new text makes no ChatGPT request.
- **Keep work across downloads and sync.** The first audio download keeps matching transcripts, insights, chat and moments usable, including the previous transcript during replacement. Backup restore and sync now reconcile the streamed and downloaded identities of the same enclosure. Different audio still requires review or preparation.
- **Summary & chapters in the background.** Generation posts a **Generating summary & chapters** notification with Pause. System-stopped work resumes from completed parts; a user pause stays paused. A process kill or a request-level network error still needs manual Resume. The shared notification channel is now called **Background tasks**, retaining its settings.
- **Library fixes.** Confirmed unsubscribe removes that show’s queue entries and listening history while keeping downloads, transcripts and saved moments. Refresh and re-follow keep concurrent show edits. Feed refresh can run on mobile data even with Wi-Fi-only downloads enabled; audio, model and transcript-download restrictions are unchanged.
- **ChatGPT recovery.** An unavailable saved AI model falls back to an available account model, with the actual selection explained in Settings. An unreadable saved connection can be replaced by signing in again.
- **More resilient backups and startup.** Missing or damaged post images are omitted with a warning instead of blocking a backup; the phone’s posts are unchanged. A failed optional startup recovery step no longer blocks the rest of startup.
- **Update checks** choose the highest version code among recent eligible releases, including when an older release line was published more recently.

Install the signed APK over the existing app; do not uninstall first. This personal alpha retains application ID `dev.distillpod.android` and the existing signing identity, and raises the version code to **54**. The database upgrades additively from schema 12 to 13 to keep a transcript during replacement. Backup format and Firebase rules/protocol are unchanged; no backend deployment is needed. Downgrading the APK is not supported.

**Known limits:**

- A forward-expanded moment whose passage ends more than 15 seconds after the tap stays on this phone and in its backups, protecting older clients’ sync. Other installations keep the previous synced version. An already-uploading older snapshot can still replace the displayed result; the extended result remains under Previous versions. Backups containing extended passages require this version or later to restore.
- Matching streamed audio by its enclosure URL assumes the publisher has not changed the audio behind that URL. Dynamic ad insertion or differently tokenized URLs can still require source review; previously flagged moments are not automatically repaired.
- Intermittent queue restoration/player disagreement remains under investigation in [#251](https://github.com/andrepaim/distillpod/issues/251). Added diagnostics and a test synchronization fix do not establish that the underlying issue is fixed.
- Real-phone background execution, notification Pause and OEM power behavior for summary generation remain unverified. The implementation was merged with an owner-recorded waiver of its phone check. No new phone installation or live-account validation was performed for this release.

**Validation:**

- Included fixes have regression tests and successful hosted CI, including default/voice builds, lint, Firebase rules, native checks and isolated API 35 acceptance. Where main reused identical-tree PR proof, that is equivalence evidence rather than a fresh emulator run.
- Galaxy S22 evidence on PR #240 (integrated revision `8ceaa58`) covers the additive schema upgrade with library counts, transcripts, models, queue, moments and posts preserved, plus confirmation/cancel/replacement and continued reading. This is feature-specific evidence, not an installation of this signed release.
- The release candidate must pass exact-revision main CI before tagging. Publication separately reruns full validation, release lint/assembly and signing. Downloaded assets are checked after publication for checksums, source identity, package/version, non-debuggable state, retained certificate and alignment.

[View changes since 0.29.0-alpha.1](https://github.com/andrepaim/distillpod/compare/v0.29.0-alpha.1...v0.30.0-alpha.1).
