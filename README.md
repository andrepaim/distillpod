<div align="center">
  <img src="branding/icon.svg" width="112" height="112" alt="DistillPod Listening Drop icon">
  <h1>DistillPod</h1>
  <p><strong>Listen deeply. Keep what matters.</strong></p>
  <p>A native Android podcast player with local transcription and saved insights.</p>
  <p>
    <a href="https://github.com/andrepaim/distillpod/actions/workflows/ci.yml"><img src="https://github.com/andrepaim/distillpod/actions/workflows/ci.yml/badge.svg?branch=main" alt="Android CI"></a>
    <img src="https://img.shields.io/badge/Android-10%2B-91DCC8" alt="Android 10 or later">
    <img src="https://img.shields.io/badge/status-personal%20alpha-B7B9FF" alt="Personal alpha">
  </p>
  <p><a href="https://github.com/andrepaim/distillpod/releases"><strong>Download the APK</strong></a> · <a href="docs/getting-started.md">Getting started</a> · <a href="docs/development.md">Build from source</a></p>
</div>

DistillPod combines everyday podcast listening with a place to keep useful ideas. Follow your shows, listen in the background, prepare a transcript on your phone, and save a moment without losing your place.

**This repository now contains the native Android app.** The previous self-hosted web version is preserved on [`legacy/web`](https://github.com/andrepaim/distillpod/tree/legacy/web), with its final source tagged [`web-legacy-final`](https://github.com/andrepaim/distillpod/tree/web-legacy-final). See [the archive guide](docs/legacy-web.md).

## What it does

| Listen | Understand | Keep |
| --- | --- | --- |
| Search podcasts with artwork and show previews | Prefer publisher transcripts when available | Capture the preceding 60 seconds |
| Follow public RSS feeds or import OPML | Transcribe audio locally with Whisper | Save excerpts, quotes and insights |
| Stream or download for offline listening | Prepare transcripts with live progress and pause/resume | Replay the original source moment |
| Play in the background with a persistent queue | Request summaries, chapters and episode chat | Back up your library and listening progress |

Optional AI features use a connected, eligible ChatGPT account. They depend on account/model access and consume your plan allowance. Podcast playback and local transcription work without that connection. A saved source moment is retained if preparation or an AI request fails.

## Install

1. Open [Releases](https://github.com/andrepaim/distillpod/releases) and download the attached `distillpod-<version>.apk` from an alpha release.
2. On your Android phone, open the APK and allow installation from your browser or file manager when Android asks.
3. Open DistillPod, search for a podcast, and follow it.

Requires **Android 10 or later**. The APK includes ARM64 and x86_64. For updates, install the newer release over the existing app. Keep the same application ID and signing identity; do not uninstall as an update step. Current alpha releases retain the certificate used by the existing personal-alpha installation.

Use the APK attached to a release for updates. CI debug artifacts use temporary runner keys and are intended for development/testing. See [release and signing details](docs/releases.md).

## Make it yours

- **Search:** find a show by name or creator, preview its cover and publisher, then Follow. Use Add RSS feed for shows outside the directory.
- **Listen:** resume from the library, build your queue, and download episodes for offline playback.
- **Transcribe:** choose Tiny or Base in Settings, download the model once, and prepare an episode transcript. English and Portuguese are available alongside automatic language detection.
- **Distill:** save the last 60 seconds while listening. The original source window is captured immediately; quotes and insights can be generated when its transcript is ready.
- **Save:** revisit source moments, replay their original audio, and export your library from Settings.

[Read the usage guide →](docs/getting-started.md)

## Your data

Your library, listening progress, queue, transcripts and saved moments live on your phone. Whisper transcription runs locally. Model and episode downloads require access to their hosts; podcast search, artwork and feed refresh contact their respective providers.

When you request an AI feature, the selected transcript context goes to the connected provider. DistillPod does not send episode audio for cloud transcription. Library backups exclude account credentials, downloaded audio and model binaries; reconnect and re-download those separately when moving to another device.

There is no required DistillPod server. See [privacy and data handling](docs/privacy.md).

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
docs/         Usage, development, release process and validation
branding/     Selected Listening Drop icon
```

[Development and test instructions →](docs/development.md)

## Current status

DistillPod is a **personal alpha**. The S22 Ultra has been used for real subscription/search, playback, download, transcription, saved-moment and account flows. A separate 60-minute screen-locked playback test passed. CI runs Kotlin tests, lint, native validation and isolated emulator acceptance before an APK can be released.

Broader devices, TalkBack, real Bluetooth/phone interruptions, sustained unplugged battery use and longer multilingual listening trials still need coverage. AI model availability is account-dependent. Source explanations are transcript-based; web research is not implemented in the Android app. See [implementation status and evidence](docs/android/implementation-status.md).

## Project notes

- [Build and test](docs/development.md)
- [Release process and signing](docs/releases.md)
- [Repository migration and web archive](docs/legacy-web.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)
- [Icon design source](design/icon-proposal/README.md)

The retired Python/React server and its Docker setup remain available in the web archive. Their setup instructions and feature list do not describe the Android app.
