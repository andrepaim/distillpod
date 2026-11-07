# Third-party notices

DistillPod uses third-party components under their respective licenses. This list identifies major dependencies; their original copyright and license notices remain authoritative.

- **whisper.cpp and ggml**: the pinned submodule under `third_party/whisper.cpp`, including its LICENSE and bundled component notices. Local Whisper model weights are downloaded separately and are not committed to this repository.
- **AndroidX / Jetpack Compose, Room, WorkManager and Media3**: Android libraries from their upstream Maven publications and AndroidX source distributions.
- **Kotlin and Kotlin coroutines**: upstream JetBrains distributions and license notices.
- **OkHttp**: upstream Square/OkHttp distribution and license notices.
- **youtubedl-android 0.18.1**: GPL-3.0 wrapper from https://github.com/yausername/youtubedl-android/tree/0.18.1, with its bundled Python, QuickJS and yt-dlp distributions and their respective notices. This introduces a GPL-covered dependency; distributing a combined APK must satisfy its source and license requirements. The upstream [LICENSE](https://github.com/yausername/youtubedl-android/blob/0.18.1/LICENSE) remains authoritative. No runtime extractor updates are downloaded by DistillPod.
- **Coil**: upstream Coil distribution and license notices.
- **Nimbus JOSE + JWT**: upstream Connect2id distribution and license notices.
- **Firebase Android Authentication, Firestore and Storage SDKs**: upstream Firebase Android SDK distributions and their bundled notices.
- **Android Credential Manager and Google Identity / Play services authentication**: upstream AndroidX and Google SDK distributions and their respective licenses and notices.
- **Manrope**: used by the optional design proposal only, bundled under the SIL Open Font License in `design/icon-proposal/assets/Manrope-OFL.txt`.

The Listening Drop icon paths were authored for DistillPod. Podcast titles, artwork and recordings belong to their publishers and are loaded from their feeds/hosts; they are not application-owned brand assets. No personal podcast libraries, credentials, podcast audio or model binaries are part of the source distribution.

## Distill confirmation sound

`app/src/main/res/raw/distill_scribble.wav` is adapted from **Marker (indelible marker), sound 0220**, recorded by **Joseph SARDIN / BigSoundBank**, released under **CC0 (public domain)**. Source: https://bigsoundbank.com/marqueur-feutre-indelebile-s0220.html . License: https://creativecommons.org/publicdomain/zero/1.0/ .

The app includes two writing strokes combined into 550 ms, with filtering, compression, fades and gain adjustment. The source recording and permitted modifications are documented in [the audio asset record](docs/audio/distill-scribble.md).
