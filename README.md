<div align="center">
  <img src="branding/mark.svg" width="96" height="106" alt="DistillPod Ember Drop">
  <h1>DistillPod</h1>
  <p><strong>The AI podcast player that runs on your own ChatGPT account.</strong></p>
  <p>Turn podcasts into ideas you can keep and use.</p>
  <p>
    <a href="https://github.com/andrepaim/distillpod/actions/workflows/ci.yml"><img src="https://github.com/andrepaim/distillpod/actions/workflows/ci.yml/badge.svg?branch=main" alt="Android CI"></a>
    <img src="https://img.shields.io/badge/Android-10%2B-2C2925" alt="Android 10 or later">
    <img src="https://img.shields.io/badge/status-personal%20alpha-B45309" alt="Personal alpha">
  </p>
  <p><a href="https://github.com/andrepaim/distillpod/releases"><strong>Download the APK</strong></a> · <a href="docs/getting-started.md">Getting started</a> · <a href="docs/development.md">Build from source</a></p>
</div>

Sign in with ChatGPT inside DistillPod, and its AI features run on the ChatGPT plan you already have. There is no AI subscription to buy from DistillPod and no API key to create. Requests count against your plan's usage allowance.

For example: you hear an idea worth keeping. Tap **Distill last 60 seconds**. DistillPod saves that moment right away and keeps playing. ChatGPT then reads the transcript around it and writes a short takeaway, with a quote when the passage has one.

https://github.com/user-attachments/assets/55f197e4-b3f2-404e-a715-b3a08b107cbb

A one-minute tour: save a moment with one tap, read the takeaway ChatGPT wrote, ask the episode a question, and share the idea.

## What your ChatGPT account adds

- **Takeaways from one tap.** Save the last 60 seconds from the player, a headphone gesture, Android Auto or the home-screen widget. The takeaway appears in **Saved** with the passage and its audio position. [Contextual distills →](docs/android/contextual-distills.md)
- **Summary and chapters.** Get an overview of the whole episode with timestamped chapters you can jump to.
- **Ask the episode.** Type a question in episode chat. Answers list **Source** timestamps; tap one to hear the passage.
- **Explain a saved idea.** **Explain this idea** gives an explanation, a practical reflection and questions to look into. It uses the saved transcript and does not search the web.
- **Share cards.** Turn a saved moment into a card image and a short caption. ChatGPT writes the words; the card is drawn on your phone in three styles (Quote, Big idea, Takeaways). [Share cards →](docs/android/social-share-cards.md)
- **Podcast suggestions.** Describe your interests and ChatGPT suggests searches. DistillPod checks the podcast feeds it finds before showing them.

Each result is a request on your ChatGPT plan. A takeaway needs the transcript for that part of the episode; the summary and episode chat need the complete transcript.

## How it works

1. **Follow a show.** Search the podcast directory, paste an RSS feed or a YouTube playlist link, or import an OPML file.
2. **Connect ChatGPT.** Tap **Connect ChatGPT** on the Welcome screen, or open **Settings → ChatGPT**. Tap **Continue with ChatGPT**, sign in in your browser, and return to the app.
3. **Listen and tap.** When something is worth keeping, tap **Distill last 60 seconds**. Open **Saved** to read the takeaway and replay the passage.

If a show has no suitable publisher transcript, download the Tiny or Base Whisper model once in **Settings → Transcript preparation**. DistillPod then transcribes episodes on your phone as you play them.

[Full usage guide →](docs/getting-started.md) · [Troubleshooting →](docs/troubleshooting.md)

## What works without ChatGPT

DistillPod is a complete podcast player on its own. You can skip ChatGPT and still:

- Search Apple's podcast directory, follow public RSS feeds and import OPML.
- Add public YouTube channels, playlists or videos. Episodes start as audio; choose **Video** in the player to watch. [Videocasts →](docs/android/videocasts.md)
- Download episodes for offline listening, keep an ordered queue, change speed, skip silence and set a sleep timer.
- Listen with the screen locked, from Android Auto or from the home-screen widget. [Android Auto →](docs/android/android-auto.md)
- Save moments. Without ChatGPT they keep their passage and audio position but get no takeaway. [Headphone capture →](docs/android/headphone-distill.md)
- Back up the library to a file you choose, for example on Google Drive, and restore it on another phone. Optional Google library sync is separate from ChatGPT. [Backups →](docs/android/drive-backup.md) · [Library sync →](docs/android/subscription-sync.md)

Transcripts are made on the phone. DistillPod uses a suitable publisher transcript when the show provides one; otherwise Whisper transcribes the audio on your phone. Episode audio is not uploaded for transcription. An optional lookup for an existing online transcript is off by default and sends only the episode title. [Transcript sources →](docs/android/online-transcripts.md)

## Limits to know first

- **Your plan's allowance.** AI requests use your ChatGPT plan's usage allowance, and a retry uses more. OpenAI decides which accounts and models are eligible; **Settings → ChatGPT** lists the models your account offers. Testing so far used the owner's ChatGPT account. Other plans, including free accounts, have not been verified.
- **Spoken questions need an API key.** **Ask about this** lets you ask a question out loud and hear a short answer. This voice preview is the one AI feature that does not use the ChatGPT sign-in: it needs an OpenAI API key that you add in **Settings → Voice**, and it uses that key's separate API allowance. Bluetooth headset voice needs Android 12 or later. [Voice setup →](docs/android/voice-mode.md) · [Headphone voice →](docs/android/headphone-voice.md)
- **AI can be wrong.** Takeaways, summaries and answers can miss context or make mistakes. Each one links back to the source audio so you can check it.
- **Text goes to OpenAI.** AI requests send the relevant transcript text, your question or your interests to OpenAI. Audio is not sent, except your spoken question when you use voice. [Privacy →](docs/privacy.md)
- **Personal alpha.** It has been checked on a small number of phones and emulators, not on every phone, car or headset. [Implementation status →](docs/android/implementation-status.md)

DistillPod is an independent open-source project. It is not affiliated with or endorsed by OpenAI. ChatGPT is a trademark of OpenAI.

## Install

1. Download `distillpod-<version>.apk` from the newest published alpha in [Releases](https://github.com/andrepaim/distillpod/releases).
2. Open the APK on your phone and allow installation from your browser or file manager when Android asks. For an update, install over the existing app; **do not uninstall first**.
3. Follow the steps in [How it works](#how-it-works).

Requires **Android 10+**; the APK includes ARM64 and x86_64. Published alpha APKs include the voice preview and keep the signing identity needed for in-place updates. CI debug artifacts use development signing keys; use a release APK to update your installed alpha.

## Your data

Your library, listening progress, queue, transcripts, saved moments and generated results are stored on your phone. There is no required DistillPod server.

| Connection | Used for |
| --- | --- |
| ChatGPT account | Takeaways and quotes, summaries and chapters, episode chat, explanations, share cards and podcast suggestions |
| OpenAI API key, optional | Spoken questions and answers (voice preview) |
| Google account, optional | Library sync; separate from ChatGPT, voice and the backup file picker |

The ChatGPT connection and the voice API key are encrypted on the phone and excluded from backups and sync. Optional library sync shares subscriptions from public feeds you approve, listening progress and saved moments; full transcripts, analyses, chat, explanations and share cards stay on the phone and move only through Backup & Restore. Backups exclude account credentials, downloaded audio and model binaries.

Podcast search, feeds, artwork and downloads contact their own providers. Cloud diagnostics are optional and off by default; when enabled in **Settings → Diagnostics**, they send sanitized technical events to Sentry, without podcast content or credentials. [Privacy and data handling →](docs/privacy.md)

## Personal alpha status

DistillPod has dated phone and emulator validation, and CI checks Kotlin tests, lint, native code, Firebase rules and isolated Android acceptance before publication. Broader Bluetooth and call interruptions, car microphones, TalkBack, sustained unplugged battery use and longer multilingual trials still need coverage. Sync does not replace independent backups; account switching and complete cloud account erasure are not implemented. [Implementation status and evidence →](docs/android/implementation-status.md)

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
