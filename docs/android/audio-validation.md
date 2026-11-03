# Android audio decoding and cancellation continuation

Date: November 1, 2026. Branch: `feat/native-android`.

This continues F05 in the [implementation plan](../android-implementation-plan.md). The previous harness required a prepared mono 16 kHz WAV. It now accepts locally selected MP3, M4A/AAC and WAV clips and supports cancellation of decoding and native inference. Gate F remains open until the target physical phone and representative podcast/language samples are tested.

## Implementation

- Android [MediaExtractor](https://developer.android.com/reference/android/media/MediaExtractor) and [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec) decode the selected file through bounded buffers. PCM16 is downmixed and resampled into a temporary mono 16 kHz WAV. Source imports are capped at 250 MB; declared duration and actual decoded sample counts enforce the ten-minute clip limit.
- Successful end-of-stream finalizes the WAV header. Only then does import replace the saved clip. Cancellation, malformed media, decoder errors and limit failures discard the temporary files and preserve the prior clip.
- Each native inference has an independent cancellation flag. Whisper encoder/compute callbacks observe it; cancelled inference raises `CancellationException` rather than publishing partial segments. Native handles use shared ownership and are released in `finally`.
- The UI exposes **Import audio clip** and **Cancel operation**. Duplicate work stays disabled until cancellation finishes. Activity destruction requests cancellation of current decoding/inference.

The streaming converter is a feasibility resampler using area averaging, not a measured production speech-quality solution. Whisper still holds the bounded normalized clip and its native copy in memory. Model loading is checked before and after cancellation but cannot be interrupted midway. Full episodes, durable jobs, checkpoint recovery, phone battery/thermal testing, Portuguese quality and word alignment remain open.

## Validation

- 49 JVM tests pass: 33 core tests and 16 Android/Robolectric tests. New coverage checks downmix/resampling across buffer boundaries, fractional sample-rate duration, decoded length limits, empty/incomplete frames and cancellation controls.
- Android lint and debug APK build pass for ARM64 and x86_64.
- Host Release native validation and real tiny.en/JFK inference pass.
- Android instrumentation exercises real WAV/MP3 decoding, generated stereo 44.1 kHz AAC decoding/resampling and real Whisper inference; decode cancellation, oversized/malformed input, native cancellation and the existing platform/playback regressions.
- All 17 instrumentation tests pass. The completed runner output is saved in [the evidence file](evidence/emulator-audio-tests.txt). The two result-parser tests and runner shell syntax check also pass.

The device suite runs in a fresh Android 16/API 36.1 x86_64 emulator data directory. The original emulator remained `RUNNING_LOCKED` after reboot and could not start non-direct-boot apps; its data was preserved. The runner now checks that user storage is unlocked before building/installing, avoiding an unresponsive instrumentation launch. No live account or plan allowance is used by these tests. Public fixtures come from the pinned Whisper checkout and the previously verified tiny.en model.

The first oversized-audio test constructed an invalid fixture by assuming the upstream WAV had a 44-byte header. Android rejected that file before duration validation. The fixture now writes an explicit canonical PCM16 header so the test reaches the intended duration check.

Development APK: `android/app/build/outputs/apk/debug/app-debug.apk`. SHA-256: `e05e3989be2680be49c441eacc9e28fb6c63b91cbb0024c9e4ccc2cc1ec3fcc9`. This is a debug-signed feasibility build, not a production release.

## Workspace recovery

The prior toolchain lived under `/tmp` and was lost after restart. JDK 17 and the pinned SDK/NDK/CMake were restored under `~/.local/share/distillpod-tools`; ignored `android/local.properties` points to that SDK.

Five Git objects were empty, including HEAD. Copies of the damaged metadata were preserved outside the repository. The document blob and three trees were reproduced from the unchanged index/content; the exact original commit `b8a014147d8d89d517acc6bc08e35392173cc189` was reconstructed and verified against its SHA-1. No branch reset or history replacement was needed. Git status/log and object-integrity checks work again.
