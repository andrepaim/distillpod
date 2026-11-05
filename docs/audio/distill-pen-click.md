# Distill pen-click confirmation sound

The selected feedback is a 180 ms excerpt of a real ballpoint pen click. It is played after a new saved-moment transaction commits, independently of transcript preparation and AI generation. App, lock-screen and Android Auto capture paths share that repository hook. Duplicate taps that reuse the recent moment, recovery, import and failed saves do not trigger it.

The default-on setting is **Settings → Import & notifications → Distill confirmation sound**. It is device-local. Playback uses media volume and the current media output, with no audio-focus request or change to podcast playback. The previous marker sample was too quiet in phone listening. This replacement uses full sample gain (previously 55%) and a −1 dBFS peak (previously −6 dBFS), giving the click about 10 dB more peak level. The media-volume setting is never changed. A sample that loads too late is skipped rather than played as delayed feedback. Audible levels and car routing still require listening on real hardware.

## Provenance

- Author: Joseph SARDIN / BigSoundBank.
- Original recording: [Pen click, sound 0613](https://bigsoundbank.com/pen-click-s0613.html).
- License stated on the source page: [CC0](https://creativecommons.org/publicdomain/zero/1.0/), including modification and redistribution.
- Downloaded source: `https://bigsoundbank.com/UPLOAD/ogg/0613.ogg`.
- Source OGG SHA-256: `8f54198799f6dfc250debb5118b85e3ffbe40ac0b0cbd9669b77db90e37091f1`.
- Bundled WAV SHA-256: `8ce0b5d6dbda07a5e64463802525375a11376f5d1c6a6aa4cda92f9527a33c6e`.
- Format: PCM 16-bit mono, 44.1 kHz, 7,938 frames (0.180 seconds).

Processing from the decoded source:

```sh
sox pen-decoded-0613.wav -b 16 distill_pen_click.wav \
  trim 1.22 0.18 highpass 150 lowpass 10000 fade t 0.001 0.18 0.015 rate 44100 gain -n -1
```

Only the small licensed UI asset is bundled. Personal recordings and large audio/model files remain excluded from source control.

## Validation

Tests were written before the save hook and sound coordinator: the initial focused run failed compilation for the missing feedback API. The subsequent focused run passed capture/feedback/settings tests. Tests cover commit-before-feedback, duplicate suppression, recovery silence, failed-save silence, optional-audio failure, cold loading, muting during loading, stale callbacks and persistence of the setting. Initial marker implementation verification passed: 309 JVM tests (85 core and 224 app), Android lint, debug APK assembly and instrumentation APK assembly. A dedicated Android emulator integration test also passed: a committed moment activates the native media stream while podcast playback remains paused. The emulator had host audio disabled, so this verifies native playback activation rather than subjective sound quality or physical car/Bluetooth routing.

The pen-click replacement passed the capture, sound-coordinator and settings regression tests, Android lint and debug APK assembly. The WAV header, 180 ms duration, unclipped samples and exact packaged APK resource were also verified.
