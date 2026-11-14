<div align="center">
  <img src="branding/icon.svg" width="112" height="112" alt="DistillPod Ember Drop icon">
  <h1>DistillPod</h1>
  <p><strong>Turn podcasts into ideas you can keep and use.</strong></p>
  <p>Ask questions as you listen, save meaningful moments, and revisit insights with their original context.</p>
  <p>
    <a href="https://github.com/andrepaim/distillpod/actions/workflows/ci.yml"><img src="https://github.com/andrepaim/distillpod/actions/workflows/ci.yml/badge.svg?branch=main" alt="Android CI"></a>
    <img src="https://img.shields.io/badge/Android-10%2B-91DCC8" alt="Android 10 or later">
    <img src="https://img.shields.io/badge/status-personal%20alpha-B7B9FF" alt="Personal alpha">
  </p>
  <p><a href="https://github.com/andrepaim/distillpod/releases"><strong>Download the APK</strong></a> · <a href="docs/getting-started.md">Getting started</a> · <a href="docs/development.md">Build from source</a></p>
</div>

DistillPod is an Android podcast player for people who listen to learn. Capture an idea while it is fresh, ask for clarification, and build a collection of takeaways connected to the episodes they came from. Return to the original audio whenever you want to check the context or hear it again.

## Highlights

### Listen to videocasts your way

Find public YouTube playlists alongside podcast search results, or add a playlist/video link directly. Episodes start in audio mode; choose **Video** in the player to watch, then return to audio at the same position. Downloads retain audio for offline listening and local transcription. YouTube availability depends on the provider, and these sources remain local to this app version. [Videocast guide →](docs/android/videocasts.md)

### Save the idea and keep listening

Tap **Distill last 60 seconds** when something catches your attention. DistillPod saves the source moment immediately, then uses its surrounding context to prepare a concise takeaway and, when the source supports one, a quote. Playback continues, and the capture stays saved even if preparation or AI generation needs a retry.

You can also save a moment from Android Auto or a headphone gesture. Enable **Previous track → Save moment** in **Settings → Playback & headphones**, then assign a gesture to Previous track in your headphone app. [Headphone setup →](docs/android/headphone-distill.md)

### Ask about what you just heard

Use **Ask about this** to pause the podcast, ask a spoken question, hear a short answer, and return to listening. A timer and live waveform show when your question is being recorded. The answer appears in a compact sheet, and **View last answer** lets you revisit it afterward.

The voice preview is included in the 0.16.0 alpha APK. Add your own OpenAI API key in **Settings → Voice**. An optional **Next track → Ask about the podcast** shortcut supports headphones; supported Android Auto hosts offer native Ask controls. Headphone voice needs setup before locking the phone, and car/headset compatibility remains an alpha limitation. [Voice setup →](docs/android/voice-mode.md) · [Headphone voice →](docs/android/headphone-voice.md)

### Understand the whole episode

Get a summary and timestamped chapters, jump to a relevant passage, or ask questions in episode chat. Answers use transcript excerpts and references back to the source, so you can follow an idea beyond a short summary and replay the passage that prompted it.

Saved moments retain their original audio position and source context. Revisit them in **Saved**, improve a takeaway as more context becomes available, or restore an earlier version. [Contextual distills →](docs/android/contextual-distills.md)

### Put saved ideas to use

Choose **Explain this idea** for an explanation, a practical reflection, and questions to investigate further. These explanations stay focused on the saved transcript.

Turn a saved moment into a **Quote & discussion** post or a researched **Infographic**. DistillPod prepares the draft, accepts revisions in natural language, and shares text or an image through Android's sharing tools. Infographics can add web research and sources; saved-idea explanations use the transcript only. [Social sharing →](docs/android/social-sharing-redesign.md)

## The listening tools behind it

| Feature | What you can do |
| --- | --- |
| Everyday playback | Search for shows, follow public RSS feeds, import OPML, download episodes, adjust speed, and listen in the background with a persistent queue |
| Android Auto | Browse your library, resume playback, seek, and save moments from a compatible car display; voice is a preview for supported hosts |
| Podcast suggestions | Request recommendations based on your interests, with real feeds checked before they appear |
| Backup & Restore | Connect a Drive backup file once, save with one tap, and restore from a dated history with a preview before merging |
| Optional library sync | Synchronize approved subscriptions, listening progress, completion and saved moments; opt into queue and supported preferences separately |

[Android Auto →](docs/android/android-auto.md) · [Backups →](docs/android/drive-backup.md) · [Library sync →](docs/android/subscription-sync.md)

## Get started

1. Download `distillpod-<version>.apk` from the newest published alpha in [Releases](https://github.com/andrepaim/distillpod/releases).
2. Open the APK on your phone and allow installation from your browser or file manager when Android asks. For an update, install over the existing app; **do not uninstall first**.
3. Follow a show and play an episode. Content preparation starts automatically by default; download a local model in Settings if the app needs one.
4. Connect ChatGPT in Settings to generate takeaways, summaries, episode answers and other AI results. Try **Distill last 60 seconds** while listening, then open **Saved**.
5. For spoken questions, add your OpenAI API key in **Settings → Voice** and grant microphone access when requested. Use **Ask about this** in the player.

Requires **Android 10+**; the APK includes ARM64 and x86_64. Bluetooth headset voice requires Android 12+. Published alpha APKs retain the signing identity needed for compatible in-place updates. CI debug artifacts use development signing keys; use a release APK to update your installed alpha.

[Full usage guide →](docs/getting-started.md) · [Troubleshooting →](docs/troubleshooting.md)

## AI connections and episode content

| Connection | Used for |
| --- | --- |
| ChatGPT account | Saved takeaways and quotes, summaries and chapters, written episode chat, explanations, recommendations and social posts; account/model eligibility and plan limits apply |
| OpenAI project API key | Spoken questions and answers; uses the key's API allowance separately from the ChatGPT connection |
| Google account, optional | Library sync; separate from ChatGPT, voice and the Drive backup file picker |

You can listen and save source moments without an AI connection. AI-generated results need the relevant connection and episode text.

DistillPod prefers suitable publisher transcripts, can optionally look for an existing online transcript, and can prepare text locally on your phone with Whisper. Preparation starts automatically during playback unless you turn it off; local model downloads remain explicit. Full-episode summaries and chat need a complete transcript. Voice needs publisher or local text covering the current listening position. [Content preparation and controls →](docs/android/online-transcripts.md)

## Your data

Your library, listening progress, queue, transcripts, saved moments and generated results are stored on your phone. Optional Firebase sync shares supported library records from approved public feeds. Full transcripts, analyses, episode chat, explanations and social posts stay local and can be transferred through Backup & Restore. Backups exclude account credentials, downloaded audio and model binaries.

Requested AI features send relevant text, questions or interests to OpenAI. Voice also streams your spoken question and selected transcript context using your API key. Infographics can send a public-topic research query for web search. Local Whisper preparation does not upload episode audio for cloud transcription. Podcast search, feeds, artwork and downloads contact their respective providers.

ChatGPT credentials and the voice API key are encrypted on the phone. Account credentials are excluded from library backups and sync. There is no required DistillPod server. Library sync is optional. Cloud diagnostics are also optional and default off: enable them in Settings → Diagnostics to share sanitized technical events and error reports with Sentry. Reports exclude podcast content and credentials; no session replay is collected. [Privacy and data handling →](docs/privacy.md)

## Personal alpha

DistillPod has dated phone and emulator validation, and CI checks Kotlin tests, lint, native code, Firebase rules and isolated Android acceptance before publication. Those checks do not establish compatibility with every phone, car or headset. Broader Bluetooth/call interruptions, car microphones, TalkBack, sustained unplugged battery use and longer multilingual trials still need coverage.

AI can miss context or make mistakes. Use the source references and audio replay to check its answers and takeaways. Sync does not replace independent backups; account switching and complete cloud account erasure remain unfinished. [Implementation status and evidence →](docs/android/implementation-status.md)

## Build from source

```sh
git clone --recurse-submodules https://github.com/andrepaim/distillpod.git
cd distillpod
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Use JDK 17, Android SDK 35, Build Tools 35.0.0, NDK 27.2.12479018 and CMake 3.22.1. Set `ANDROID_HOME` or create an ignored `local.properties`.

Ordinary source/debug builds keep voice disabled; add `-PdistillpodVoiceEnabled=true` to enable the preview. They also omit production Firebase configuration. See [development instructions](docs/development.md) for configured builds, tests and emulator checks, and [release instructions](docs/releases.md) for signing and publication.

## Project references

- [Documentation index](docs/README.md)
- [Release process and signing](docs/releases.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)
- [Listening Drop icon design](design/icon-proposal/README.md)
- [Repository history and web archive](docs/legacy-web.md)

This repository contains the native Android app. The earlier web app is archived on [`legacy/web`](https://github.com/andrepaim/distillpod/tree/legacy/web), with its final source tagged [`web-legacy-final`](https://github.com/andrepaim/distillpod/tree/web-legacy-final).

## License and source

The original Android app, shared core and their original build/release scripts are distributed under **GNU GPL version 3 only**. See [app/LICENSE](app/LICENSE), [core/LICENSE](core/LICENSE) and [third-party notices](THIRD_PARTY_NOTICES.md). Independent legacy/web code, publisher content, model weights and personal data retain their own terms.

APK releases include a matching source archive with the pinned Whisper source and checksum-verified upstream source archives, plus a component source manifest and notices. The app also provides license/source links in **Settings → About DistillPod**.
