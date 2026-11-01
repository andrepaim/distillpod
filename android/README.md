# DistillPod Android

Native feasibility build for ChatGPT subscription access and local Whisper transcription. Full implementation scope and outstanding gates are in [the plan](../docs/android-implementation-plan.md) and [implementation status](../docs/android/implementation-status.md).

## Build and automated tests

Requires JDK 17, Android SDK platform/build-tools 35, NDK 27.2.12479018 and CMake 3.22.1. Set `ANDROID_HOME` to your SDK or create an ignored `local.properties` containing `sdk.dir=/path/to/sdk`.

```sh
git submodule update --init --recursive
cd android
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. It is a development APK using a local debug signing key, not a production-signed release. Supported packaged ABIs are ARM64 and x86_64; minimum Android version is 10.

## Account check on a device

1. Install the APK and select Continue with ChatGPT.
2. Sign in using the account that owns your eligible plan and authorize plan usage.
3. Return to DistillPod, supply a short non-sensitive excerpt and run AI check.
4. Verify a completed response, then restart the app and repeat. Record account tier, model access and any errors without including tokens or callback URLs.
5. Disconnect when finished.

A connected badge alone does not establish inference entitlement. Account usage comes from the existing plan allowance. No API key or credential from the installed ChatGPT app is used.

## Local transcription check

Obtain an upstream GGML tiny/base Whisper model. Import it with Import Whisper model. Import a mono 16 kHz PCM16 WAV clip, at most ten minutes, then select Transcribe clip. Output reports segment timestamps in milliseconds and elapsed inference time. The app saves the latest transcription report in its private files directory.

Convert a sample with your local ffmpeg:

```sh
ffmpeg -i episode.mp3 -t 60 -ar 16000 -ac 1 -c:a pcm_s16le clip.wav
```

The app imports only files selected through the Android document picker. Audio transcription runs on the phone. The separately entered text excerpt is sent to OpenAI only when Run AI check is selected.

## Host native smoke test

The native bridge can also be checked on Linux without an Android device:

```sh
cmake -S app/src/main/cpp -B /tmp/distillpod-native -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/distillpod-native -j4
ctest --test-dir /tmp/distillpod-native --output-on-failure
/tmp/distillpod-native/transcriber_test /path/to/ggml-tiny.en.bin third_party/whisper.cpp/samples/jfk.wav
```

The last command checks real transcription content and timed output; model files are not downloaded implicitly by tests. It does not measure Android performance.

## Dependency notices

Whisper is pinned through the submodule commit. Its MIT license and bundled ggml notices are in `third_party/whisper.cpp`. No code from the noncommercial Sign in with ChatGPT DevKit was copied. The provider implements the published protocol using Nimbus JOSE JWT, OkHttp and platform cryptography. Review all application and model licenses before distribution.
