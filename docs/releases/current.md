DistillPod 0.10.1-alpha.1 adds optional lookup of existing online transcripts before on-device Whisper.

- Enable **Settings → Local transcription → Look for existing online transcripts** to search PodcastTranscript.ai for an already available transcript of an approved public show. It defaults to off and never uploads audio or creates cloud transcription jobs.
- Preparation prefers a suitable publisher transcript, then the optional service lookup, then local Whisper. Enclosure identity, duration and timed passages are checked before adoption. Missing results, service errors and offline use retain the local fallback.
- Online transcripts support reading, summaries, chapters and episode chat. Existing text stays available while a local replacement is paused or retried. Saved audio windows and automatic ad skipping still require publisher or local timing.
- Downloaded online text and provenance are included in manual backups. Lookup consent remains device-local. Database upgrades preserve the existing library and listening data.
- Includes the searchable show gallery, Saved/transcript search, grouped Settings, and 10-second rewind / 30-second forward player controls from the preceding UI review.

This release retains the personal-alpha signing identity and the existing Firebase client configuration. Paid content sync remains disabled. Install over the compatible alpha app; do not uninstall. Android 10+; ARM64 and x86_64.

Validation before publishing: 248 JVM tests, debug/release lint and APK builds, upgrades from database schemas 1–7, and an S25 in-place update with before/after library preservation checks. GitHub CI also gates publication on native, Firebase and isolated Android acceptance checks. See [online transcript design](https://github.com/andrepaim/distillpod/blob/main/docs/android/online-transcripts.md).
