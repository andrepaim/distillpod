# Getting started

Install the APK attached to a [GitHub release](https://github.com/andrepaim/distillpod/releases). Android 10 or later is required. A newer compatible release installs over the current app; keep the application installed to retain its private data.

## Connect ChatGPT

Start in **Settings → ChatGPT → Continue with ChatGPT**. The connection powers DistillPod's quotes and insights, episode summaries and chapters, episode chat, saved-idea explanations and interest-based podcast suggestions. Account/model eligibility and plan limits apply; requests use your ChatGPT plan allowance.

Complete browser sign-in and return to DistillPod for its final connection result. A provider email alone does not establish that the app finished connecting. A retry can use more allowance. Playback, local transcription and source-moment capture also work without the connection.

## Follow your shows

Open Search and enter a show name or creator. Preview the artwork and publisher, then Follow. The directory is external, so a show might require its public RSS address instead. Add RSS feed accepts that address directly. Settings also imports OPML subscription lists.

A subscription fetches feed metadata and episode listings. Download audio explicitly from an episode when you want offline playback. Search history is local and can be cleared.

## Listen

The library offers Continue listening, followed shows and recent episodes. Use Play next or Add to queue to choose what comes next. The mini-player opens the full player and queue. Playback can continue with the screen locked; Android media controls offer transport actions.

Downloaded episodes can play without a network connection. Streaming requires access to the publisher's audio host. Listen from the original source timeline; reviewed ad ranges are optional, and seeking into a range lets you hear it normally.

## Prepare a transcript

Publisher transcripts are used when suitable. Otherwise choose Tiny or Base in Settings and download the model. Prepare the episode transcript from its tools. Local processing shows its current phase and progress, and completed chunks remain available after pause/resume. Longer recordings take time and use battery; model and language choices affect performance.

Automatic language detection, English and Português are available. A partial transcript is identified as partial rather than treated as complete.

## Understand an episode

Once the complete transcript is ready, open **Summary & chapters** to generate an overview and jump to chapter timestamps, or open episode chat to ask questions about what was said. Answers use transcript excerpts and source references; replay the relevant audio to check the context.

## Keep a moment

While listening, select Distill last 60 seconds. DistillPod records that original source window immediately. Open Saved to revisit the excerpt and replay it. Source preparation or AI failure retains the capture for review and retry.

With ChatGPT connected and the source transcript ready, DistillPod can generate a quote and concise insight for the saved moment. Source review may be required before generation. Choose **Explain this idea** to explore the excerpt further through an explanation, practical reflection and questions to investigate. Explanations use the transcript; they do not search the web.

## Back up or move devices

Settings → Backup & Restore → Create backup file connects a Drive document once, with a suggested filename. After setup, Back up now saves without another picker, and Restore a backup shows dated snapshots in the app. Reconnect that file once after reinstalling or on another phone. Import older backup supports standalone JSON files and shows all file types. Restore previews and safely merges data, keeping existing local records. See the [Drive backup guide](android/drive-backup.md) for retention, privacy and recovery.

Credentials, downloaded audio, model binaries and private device file paths are excluded. Reconnect the account and re-download audio/models on a destination installation. OPML transfers subscriptions only.

Developer checks are diagnostics for deliberate testing. They are not needed for normal listening, and fixture suites belong on an isolated emulator.
