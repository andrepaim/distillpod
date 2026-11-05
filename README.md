<div align="center">
  <img src="branding/icon.svg" width="112" height="112" alt="DistillPod Listening Drop icon">
  <h1>DistillPod</h1>
  <p><strong>Listen deeply. Keep what matters.</strong></p>
  <p>An AI podcast player for Android. Understand episodes, explore ideas and save insights.</p>
  <p>
    <a href="https://github.com/andrepaim/distillpod/actions/workflows/ci.yml"><img src="https://github.com/andrepaim/distillpod/actions/workflows/ci.yml/badge.svg?branch=main" alt="Android CI"></a>
    <img src="https://img.shields.io/badge/Android-10%2B-91DCC8" alt="Android 10 or later">
    <img src="https://img.shields.io/badge/status-personal%20alpha-B7B9FF" alt="Personal alpha">
  </p>
  <p><a href="https://github.com/andrepaim/distillpod/releases/tag/v0.10.1-alpha.1"><strong>Download the APK</strong></a> · <a href="docs/getting-started.md">Getting started</a> · <a href="docs/development.md">Build from source</a></p>
</div>

DistillPod is for people who listen to podcasts to learn. Use AI to summarize an episode, ask questions about what was said, and turn a moment worth remembering into a quote and insight you can revisit. Each saved moment keeps its place in the original audio, so you can return to the source.

Connect your ChatGPT account for episode understanding and saved insights. Episode understanding uses publisher transcripts, optional existing online transcripts, or Whisper running locally on your phone. Optional Google sign-in synchronizes supported library data between installations; ChatGPT and Google are separate connections.

The latest published alpha is [**0.10.1-alpha.1**](https://github.com/andrepaim/distillpod/releases/tag/v0.10.1-alpha.1) (code 19), released November 5, 2026, matching the current source version. It includes optional existing online transcript lookup, the searchable show gallery, Saved/transcript search, grouped Settings and optional Google library sync. See [implementation status](docs/android/implementation-status.md) for delivered features and validation evidence.

## Turn listening into understanding

An idea catches your attention. Tap **Distill last 60 seconds** and keep listening. DistillPod saves the source window immediately, then uses its transcript to extract a quote and explain the central idea. Open Saved to revisit it, explore it further or replay the original moment. Your capture stays saved if transcription or an AI request needs a retry.

| What you want to do | How DistillPod helps |
| --- | --- |
| Keep an idea from what you just heard | Capture the preceding minute and generate a quote and concise insight from its transcript |
| Get an overview of an episode | Generate a summary and timestamped chapters, then jump to the relevant audio |
| Ask about something the speakers said | Chat with the episode using transcript excerpts and references back to the source |
| Explore a saved idea | Request an explanation, a practical reflection and questions to investigate further |
| Find more to listen to | Request podcast suggestions based on your interests, with real feeds checked before results appear |

The listening tools support that workflow: podcast search, public RSS subscriptions, OPML import, background playback, a persistent queue and offline downloads. Connect a Drive backup file once for one-tap saves and a dated restore history. Backups preserve your library, listening progress, transcripts and saved insights.

## ChatGPT and transcription

**AI is central to the DistillPod experience.** Connect an eligible ChatGPT account in Settings to generate quotes and insights, summaries and chapters, episode answers, explanations and podcast suggestions. Requests use your ChatGPT plan allowance; available models and usage limits depend on your account.

Episode understanding starts with a transcript. DistillPod prefers a suitable publisher transcript. You can enable [existing online transcript lookup](docs/android/online-transcripts.md) in Settings before falling back to Whisper on your phone. Lookup is off by default, uses approved public shows, and never uploads audio or creates cloud transcription jobs. For local transcription, choose Tiny or Base, with automatic language detection or an explicit English or Portuguese setting. Preparation shows progress and supports pause/resume. Full-episode summaries and chat need a complete transcript; saved moments can use the transcript for their source window.

Playback, local transcription and source-moment capture also work without ChatGPT, but DistillPod is designed around the connected AI experience.

## Install

1. Open the [0.10.1-alpha.1 release](https://github.com/andrepaim/distillpod/releases/tag/v0.10.1-alpha.1) and download the attached `distillpod-0.10.1-alpha.1.apk`.
2. On your Android phone, open the APK and allow installation from your browser or file manager when Android asks.
3. Open **Settings → ChatGPT**, choose **Continue with ChatGPT**, and complete sign-in to connect your account.
4. Search for a podcast, follow it and open an episode. Choose **Prepare transcript**; if a local model is needed, download Tiny or Base in Settings.
5. With a complete transcript, try **Summary & chapters** or ask the episode a question. While listening, use **Distill last 60 seconds** to start your saved collection.

Requires **Android 10 or later**. The APK includes ARM64 and x86_64. For updates, install the newer release over the existing app. Keep the same application ID and signing identity; do not uninstall as an update step. Current alpha releases retain the certificate used by the existing personal-alpha installation.

Use the APK attached to a release for updates. CI debug artifacts use temporary runner keys and are intended for development/testing. See [release and signing details](docs/releases.md).

[Read the usage guide →](docs/getting-started.md) · [Back up your library →](docs/android/drive-backup.md)

## Your data

Your library, listening progress, queue, transcripts and saved moments are stored on your phone. Optional [library sync](docs/android/subscription-sync.md) sends approved public subscriptions, progress, completion and saved moments to Firebase; queue and portable preferences are an additional opt-in. Full transcripts, analyses, episode chat and explanations stay local. Whisper transcription runs locally. Model and episode downloads require access to their hosts; podcast search, artwork and feed refresh contact their respective providers.

AI requests send the relevant transcript text, questions or interests to OpenAI through your connected ChatGPT account. Podcast suggestions include followed-show titles only when you select that option. DistillPod does not send episode audio for cloud transcription.

Library backups include saved insights and chat history, but exclude account credentials, downloaded audio and model binaries; reconnect and re-download those separately when moving to another device.

There is no required DistillPod server. Firebase sync is optional, and live analytics/crash collection is not enabled. See [privacy and data handling](docs/privacy.md).

## Current status

DistillPod is a **personal alpha**. The S22 Ultra has been used for real subscription/search, playback, download, local transcription and saved-moment flows, along with ChatGPT sign-in, quote/insight generation, summaries and episode chat. A separate 60-minute screen-locked playback test passed. CI runs Kotlin tests, lint, native validation and isolated emulator acceptance before an APK can be released.

[Optional library sync](docs/android/subscription-sync.md) is implemented on `main`, with Google sign-in, offline changes and explicit conflict choices. It requires a configured build; ordinary source/debug builds show sync as unavailable, while the release workflow requires Firebase client configuration. The selected deployment uses Spark with billing disabled. Transcripts and other finished text stay local and can be transferred through Backup & Restore; the experimental Cloud Storage extension is disabled in builds. Multiple local account profiles, complete account erasure, independent dated cloud recovery and live telemetry remain later milestones in the [implementation plan](docs/android/firebase-sync-telemetry-plan.md).

AI summaries, answers and explanations use the episode transcript and can miss context or contain mistakes; use the source references and audio replay to check them. Saved-idea explanations currently use the transcript only; web research is not implemented in the Android app.

Broader devices, TalkBack, real Bluetooth/phone interruptions, sustained unplugged battery use and longer multilingual listening trials still need coverage. See [implementation status and evidence](docs/android/implementation-status.md).

## Build from source

```sh
git clone --recurse-submodules https://github.com/andrepaim/distillpod.git
cd distillpod
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Use **JDK 17**, Android SDK 35, Build Tools 35.0.0, NDK 27.2.12479018 and CMake 3.22.1. Set `ANDROID_HOME` or create your own ignored `local.properties`.

The Gradle project is at the repository root:

```text
app/          Native Android UI, player, library, workers and JNI
core/         Android-independent Kotlin logic
third_party/  Pinned whisper.cpp submodule
native-tests/ Host-side native bridge validation
scripts/      Emulator, fixture and release tooling
firebase/     Sync security rules and isolated emulator tests
docs/         Usage, development, release process and validation
branding/     Selected Listening Drop icon
```

[Development and test instructions →](docs/development.md)

## Project notes

This repository contains the native Android app. The previous self-hosted web version is preserved on [`legacy/web`](https://github.com/andrepaim/distillpod/tree/legacy/web), with its final source tagged [`web-legacy-final`](https://github.com/andrepaim/distillpod/tree/web-legacy-final).

- [Build and test](docs/development.md)
- [Release process and signing](docs/releases.md)
- [Optional library sync — usage, configuration and limitations](docs/android/subscription-sync.md)
- [Firebase sync and telemetry — implementation plan and remaining gates](docs/android/firebase-sync-telemetry-plan.md)
- [Current implementation status and validation evidence](docs/android/implementation-status.md)
- [Repository migration and web archive](docs/legacy-web.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)
- [Existing online transcripts before Whisper](docs/android/online-transcripts.md)
- [Icon design source](design/icon-proposal/README.md)

The retired Python/React server and its Docker setup remain available in the web archive. Their setup instructions and feature list do not describe the Android app.
