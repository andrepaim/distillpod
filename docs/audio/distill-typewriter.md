# Distill typewriter confirmation sound

The feedback is a 550 ms excerpt containing two real mechanical typewriter keystrokes. It plays after a new saved-moment transaction commits, independently of transcript preparation and AI generation. App, lock-screen and Android Auto capture paths share that repository hook. Duplicate taps that reuse the recent moment, recovery, import and failed saves do not trigger it.

The default-on setting is **Settings → Import & notifications → Distill confirmation sound**. It is device-local. Playback uses media volume and the current media output, with no audio-focus request or change to podcast playback. A sample that loads too late is skipped. Real hardware listening is required to assess perceived loudness and car routing.

The marker and pen previews were too subtle during phone listening. This replacement is longer and uses compression to bring out the mechanical body. Its whole-clip RMS is approximately −20.5 dBFS versus −34.4 dBFS for the pen, with the same −1 dBFS peak and full SoundPool gain. This measures roughly 14 dB more average signal level; it does not guarantee the same perceived difference on every speaker or volume setting. Device media volume is never changed.

## Provenance

- Author: Joseph SARDIN / BigSoundBank.
- Original recording: [Typewriter #1, sound 1065](https://bigsoundbank.com/typewriter-s1065.html).
- License stated on the source page: [CC0](https://creativecommons.org/publicdomain/zero/1.0/), including modification and redistribution.
- Downloaded source: `https://bigsoundbank.com/UPLOAD/ogg/1065.ogg`.
- Source OGG SHA-256: `bd36b5b5c775e0f1a7a9125e4bf0254d9ffdb0fe77a92c04624f8c6c2d6e988e`.
- Bundled WAV SHA-256: `29419124a8547c3e14d171cc695cad7eae986495d7836cb91d97ca27c83f82da`.
- Format: PCM 16-bit mono, 44.1 kHz, 24,255 frames (0.550 seconds).

Processing from the decoded source:

```sh
sox typewriter-decoded-1065.wav -b 16 distill_typewriter.wav \
  trim 10.995 0.55 gain -6 highpass 180 lowpass 8000 \
  compand 0,0.003 6:-86,-86,-42,-42,-18,-30,0,-25.5 \
  fade t 0.003 0.55 0.025 rate 44100 gain -n -1
```

Input gain provides filter headroom; compression reduces transient-to-body contrast, and the final normalization leaves 1 dB of sample headroom. Only this small licensed UI asset is bundled. Personal recordings and large audio/model files remain excluded from source control.

## Validation

The original save hook and coordinator were implemented with tests written first. The initial implementation passed 309 JVM tests, lint, APK assembly and an isolated emulator test confirming native playback activation without starting podcast playback. These behavior tests cover durable-save ordering, duplicate suppression, failed saves, recovery, optional audio failures, cold loading, stale callbacks, muting and setting persistence.

For this asset revision, the WAV duration, mono PCM format, peak headroom and whole-clip RMS were measured. Sound listening on the S22 remains a user acceptance check.

Revision checks passed: all 15 capture, sound-coordinator and settings regression tests; Android lint; debug APK assembly; and byte-for-byte verification of the replacement WAV inside the APK.
