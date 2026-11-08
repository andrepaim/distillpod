# Videocast delivery evidence

Branch: `feat/videocast-reliability`, based on main `8f90899`. Date: 2026-11-08. Candidate: 0.19.0-alpha.1, code 36. The original feature is preserved at `9966710`; integration retains main's reliability telemetry. The final merged source and publication workflow are identified by release build-info. Final execution receipts, including the separate locked-screen publication gate, are recorded in [PR #16](https://github.com/andrepaim/distillpod/pull/16).

## Automated checks

- 94 core and 369 Android JVM tests passed in both voice-disabled and voice-enabled configurations. Configured debug/release lint and APK assembly passed.
- 28 tooling tests passed, including committed-source-only packaging, rejection of changed upstream archives and prevention of wrong executable payloads replacing an existing artifact.
- Both packaged ABIs contain ten ELF libraries in total with 16 KiB load-segment alignment. The two Python files named `.so` are ZIP archives and are retained without symbol stripping.
- A real source distribution was assembled from committed project/Whisper sources and three checksum-verified upstream source archives. Source archives contain no untracked operator files. Public source manifests, notices and the app/core GPL licenses accompany the distribution.
- Public live emulator acceptance decoded audio and rendered a video frame with the updated extractor. Synthetic playback, source swapping during preparation and queue tests passed. Full PR CI on `dc42912` passed native/Firebase checks, both voice build configurations and isolated Android/cloud/voice acceptance. Later test-only/evidence changes require their own final CI run.

## Physical S22

The configured local debug candidate was installed in place using the retained certificate. It preserves accounts, keys, models, subscriptions and other owner data. Debug instrumentation supports the private long-playback recorder; this is distinct from the non-debuggable published APK. No ordinary destructive emulator suite, reset, uninstall, paid AI request or account-cookie extraction ran on this phone.

- Repeated acceptance exposed a persistent switch-to-audio reset from 5,015 ms to zero while the prior timeline was preparing. A trace confirmed zero after READY, without a decoder error. Source swaps now send queue and starting position in one command. Failed trials restored the owner queue/settings and were not counted as passes.
- After the fix, three cold-process rounds passed: nine real audio/video decodes across three public videos, with paused position, speed and audio/video source changes checked in each round.
- A separate physical queue check passed: enqueue preserves the active video item; the next episode starts in audio mode; cleanup belonging to the old episode does not disable the new episode's selected video.
- A complete public episode downloaded: **29,976,124 bytes**, decoded duration **1,852,139 ms (30m52s)**. The validated local file and canonical episode URL were persisted.
- Local playback passed with Wi-Fi/mobile data disabled, and again after force-stop/reopen while offline. Original network enablement was restored.
- Full local Whisper transcription reached READY with **565 segments**. A second verification found duration, processed duration and saved coverage all equal to **1,852,139 ms**. This establishes pipeline completion, not a word-accuracy benchmark.
- The actual player view displayed a video frame at the preserved five-second position. The in-app GPL license dialog opened successfully.
- Owner fixture download/transcript/rows were removed; the original queue, position, speed, player volume and automatic-transcription preference were restored. Global temporary screen/media-volume settings are handled by the private test host and final cleanup.

## Locked-screen gate

The release owner additionally requires a measured 60-minute locked, USB-powered, muted audio trial outside instrumentation, using the explicit playback-reliability protocol. The test stores/restores the owner queue, speed, position and player volume, and temporarily disables automatic transcription for that fixture. The final elapsed time, interruptions and cleanup result are recorded in PR #16. This gate does not establish unplugged battery drain, audible sound quality, real Bluetooth/head-unit behavior or native crash collection.

## Cloud observations

Actual S22 extraction and transcription-stage events were observed through the connected Sentry tools under the configured staging release. The playback dashboard's new YouTube/videocast widget was deployed, read back and its production query executed successfully. Test traffic is excluded; event counts are not inferred success-rate denominators. No raw audio, transcript, video identifiers, titles, URLs or extractor output are included in these reports.
