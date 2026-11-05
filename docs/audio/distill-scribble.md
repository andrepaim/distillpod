# Distill pen-scribble confirmation sound

The feedback is two real felt-tip pen strokes on paper, combined into 550 ms. It plays after a new saved-moment transaction commits, independently of transcript preparation and AI generation. App, lock-screen and Android Auto capture paths share that repository hook. Duplicate taps that reuse the recent moment, recovery, import and failed saves do not trigger it.

The default-on setting is **Settings → Import & notifications → Distill confirmation sound**. It is device-local. Playback uses media volume and the current media output, with no audio-focus request or change to podcast playback. A sample that loads too late is skipped.

The owner approved the typewriter preview's level and duration but requested a pen scribble instead. This asset retains exactly 550 ms and matches that preview's whole-clip RMS (−20.54 dBFS). Its peak is −8.44 dBFS, giving more sustained writing texture instead of isolated mechanical spikes. Matching RMS is an objective signal-level check; listening on the S22 remains necessary to judge perceived loudness and character. Device media volume is never changed.

## Provenance

- Author: Joseph SARDIN / BigSoundBank.
- Original recording: [Marker (indelible marker), sound 0220](https://bigsoundbank.com/marqueur-feutre-indelebile-s0220.html).
- Source-page license: [CC0](https://creativecommons.org/publicdomain/zero/1.0/), including modification and redistribution.
- Downloaded source: `https://bigsoundbank.com/UPLOAD/ogg/0220.ogg`.
- Source OGG SHA-256: `6de7608110911113df9e9bdc1673dea875b410a9b0d134866f1d1e6da18424e1`.
- Bundled WAV SHA-256: `9b893a026956ebfd55bff107943b9ba85781854f8c62621b4c2400f118ddf37e`.
- Format: PCM 16-bit mono, 44.1 kHz, 24,255 frames (0.550 seconds).

Processing from the decoded source:

```sh
sox marker-decoded-0220.wav -b 16 stroke-a.wav \
  trim 6.02 0.28 fade t 0.004 0.28 0.008
sox marker-decoded-0220.wav -b 16 stroke-b.wav \
  trim 6.88 0.27 fade t 0.004 0.27 0.015
sox stroke-a.wav stroke-b.wav -b 16 processed.wav \
  gain -6 highpass 180 lowpass 8000 \
  compand 0,0.003 6:-86,-86,-42,-42,-18,-30,0,-25.5 \
  rate 44100 gain -n -1
sox processed.wav -b 16 distill_scribble.wav gain -7.441789
```

Short fades soften the join. Input gain provides filter headroom; compression brings out the writing texture. The final gain matches the accepted typewriter reference's RMS. Only the small licensed UI asset is bundled. Personal recordings and large audio/model files remain excluded from source control.

## Validation

The original save hook and coordinator were implemented with tests written first. The initial implementation passed 309 JVM tests, lint, APK assembly and an isolated emulator test confirming native playback activation without starting podcast playback. Behavior tests cover durable-save ordering, duplicate suppression, failed saves, recovery, optional audio failures, cold loading, stale callbacks, muting and setting persistence.

For this revision, the WAV duration, mono PCM format, absence of clipping and RMS match within 0.1 dB were verified. Sound character and car routing require real hardware listening.

Revision checks passed: all 15 capture, sound-coordinator and settings regression tests; Android lint; debug APK assembly; and byte-for-byte verification of the replacement WAV in the APK.
