# Videocast delivery evidence

Integration branch: `feat/videocast-reliability`, based on main `8f90899`. Date: 2026-11-08. Candidate: 0.19.0-alpha.1, code 36. Validation is in progress; this receipt is not proof of publication.

The original feature is preserved in `feat/youtube-videocasts` at `9966710`. Integration retains the reliability telemetry from main.

## Automated evidence

The configured voice-enabled candidate passed 94 core and 369 app JVM tests, debug/release lint and APK assembly. There are 27 tooling tests, including source-archive credential exclusion and upstream checksum rejection. Both packaged ABIs have ten ELF libraries in total with 16 KiB load-segment alignment; Python archives stored under a `.so` name are ZIP archives, not ELF objects.

## Device evidence

The original and updated extractors each passed deliberate live emulator playback: public search, playlist loading, audio decoding and a rendered video frame. The three synthetic playback/queue tests also passed before the final source-swap fix. They will be rerun on the final implementation.

On the S22, one cold-start round decoded three public videos and passed service mode switching. A repeated round exposed a persistent position reset when switching back to audio during timeline preparation. A trace confirmed that the player settled at zero without a decoder error. Source swaps now carry the full queue and starting position in one command. Physical acceptance is pending the fixed candidate; the failed runs are not counted as passing evidence. Owner queue/settings cleanup completed after both failed trials.

The physical candidate uses the retained signing identity and an in-place update. Temporary device settings and owner state stay in private operator storage; public receipts contain only synthetic/public fixture outcomes. No paid AI request or account-cookie extraction is part of these tests.
