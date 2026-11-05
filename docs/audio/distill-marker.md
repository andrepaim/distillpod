# Distill marker confirmation sound

The selected feedback is a 420 ms excerpt of a real marker writing on paper. It is played after a new saved-moment transaction commits, independently of transcript preparation and AI generation. App, lock-screen and Android Auto capture paths share that repository hook. Duplicate taps that reuse the recent moment, recovery, import and failed saves do not trigger it.

The default-on setting is **Settings → Import & notifications → Distill confirmation sound**. It is device-local. Playback uses media volume and the current media output, with no audio-focus request or change to podcast playback. A sample that loads too late is skipped rather than played as delayed feedback. Audible levels and car routing still require listening on real hardware.

## Provenance

- Author: Joseph SARDIN / BigSoundBank.
- Original recording: [Marker (indelible marker), sound 0220](https://bigsoundbank.com/marqueur-feutre-indelebile-s0220.html).
- License stated on the source page: [CC0](https://creativecommons.org/publicdomain/zero/1.0/), including modification and redistribution.
- Downloaded source: `https://bigsoundbank.com/UPLOAD/ogg/0220.ogg`.
- Source OGG SHA-256: `6de7608110911113df9e9bdc1673dea875b410a9b0d134866f1d1e6da18424e1`.
- Bundled WAV SHA-256: `b10e8fdf702d44b4b375d90305ed939854774f60ad2b9027521db4f75f9bf0c3`.
- Format: PCM 16-bit mono, 44.1 kHz, 18,522 frames (0.420 seconds).

Processing from the decoded source:

```sh
sox marker-decoded-0220.wav -b 16 distill_marker.wav \
  trim 6.02 0.42 highpass 150 lowpass 6000 fade t 0.005 0.42 0.015 gain -n -6
```

Only the small licensed UI asset is bundled. Personal recordings and large audio/model files remain excluded from source control.

## Validation

Tests were written before the save hook and sound coordinator: the initial focused run failed compilation for the missing feedback API. The subsequent focused run passed capture/feedback/settings tests. Tests cover commit-before-feedback, duplicate suppression, recovery silence, failed-save silence, optional-audio failure, cold loading, muting during loading, stale callbacks and persistence of the setting. Verification passed: 309 JVM tests (85 core and 224 app), Android lint, debug APK assembly and instrumentation APK assembly. A dedicated Android emulator integration test also passed: a committed moment activates the native media stream while podcast playback remains paused. The emulator had host audio disabled, so this verifies native playback activation rather than subjective sound quality or physical car/Bluetooth routing.
