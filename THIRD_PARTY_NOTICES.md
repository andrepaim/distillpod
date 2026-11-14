# Third-party notices

DistillPod uses third-party components under their respective licenses. This list identifies major dependencies; their original copyright and license notices remain authoritative.

The original Android application (`app/`) shared core (`core/`) and their original build/release scripts are licensed under **GNU GPL version 3 only**. See [app/LICENSE](app/LICENSE) and [core/LICENSE](core/LICENSE). Third-party components retain their original licenses. This grant does not change independent legacy/web code, publisher content, model weights or personal data.

- **whisper.cpp and ggml**: the pinned submodule under `third_party/whisper.cpp`, including its LICENSE and bundled component notices. Local Whisper model weights are downloaded separately and are not committed to this repository.
- **AndroidX / Jetpack Compose, Palette, Room, WorkManager and Media3**: Android libraries from their upstream Maven publications and AndroidX source distributions.
- **Kotlin and Kotlin coroutines**: upstream JetBrains distributions and license notices.
- **OkHttp**: upstream Square/OkHttp distribution and license notices.
- **youtubedl-android 0.18.1**: GPL-3.0 wrapper from https://github.com/yausername/youtubedl-android/tree/0.18.1, with its bundled Python, QuickJS and yt-dlp distributions and their respective notices. This introduces a GPL-covered dependency; distributing a combined APK must satisfy its source and license requirements. The upstream [LICENSE](https://github.com/yausername/youtubedl-android/blob/0.18.1/LICENSE) remains authoritative. No runtime extractor updates are downloaded by DistillPod.
- **yt-dlp 2026.10.26 and its bundled EJS scripts**: checksum-pinned upstream executable packaged at build time from https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.26. See its bundled notices and the release source manifest.
- **Coil**: upstream Coil distribution and license notices.
- **Nimbus JOSE + JWT**: upstream Connect2id distribution and license notices.
- **Firebase Android Authentication, Firestore and Storage SDKs**: upstream Firebase Android SDK distributions and their bundled notices.
- **Android Credential Manager and Google Identity / Play services authentication**: upstream AndroidX and Google SDK distributions and their respective licenses and notices.
- **Inter and Newsreader**: interface and reading typefaces bundled in `app/src/main/res/font/` under the SIL Open Font License 1.1. They are static instances (Inter 400/500/600/700, Newsreader 400/500/400 italic) subset to Latin, cut from the variable fonts in https://github.com/google/fonts (`ofl/inter`, `ofl/newsreader`) on 14 Nov 2026. The licenses ship in the app as `assets/licenses/Inter-OFL.txt` and `assets/licenses/Newsreader-OFL.txt`.
- **Manrope**: used by the optional design proposal only, bundled under the SIL Open Font License in `design/icon-proposal/assets/Manrope-OFL.txt`.

The Listening Drop and Ember Drop icon paths were authored for DistillPod. Podcast titles, artwork and recordings belong to their publishers and are loaded from their feeds/hosts; they are not application-owned brand assets. No personal podcast libraries, credentials, podcast audio or model binaries are part of the source distribution.

## Distill confirmation sound

`app/src/main/res/raw/distill_scribble.wav` is adapted from **Marker (indelible marker), sound 0220**, recorded by **Joseph SARDIN / BigSoundBank**, released under **CC0 (public domain)**. Source: https://bigsoundbank.com/marqueur-feutre-indelebile-s0220.html . License: https://creativecommons.org/publicdomain/zero/1.0/ .

The app includes two writing strokes combined into 550 ms, with filtering, compression, fades and gain adjustment. The source recording and permitted modifications are documented in [the audio asset record](docs/audio/distill-scribble.md).
