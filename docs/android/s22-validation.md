# Samsung Galaxy S22 Ultra validation

> Historical validation report. Results and commands below apply to the stated
> revision and device. For current behavior, use the [usage guide](../getting-started.md),
> [build instructions](../development.md) and [implementation status](implementation-status.md).

Date: November 1, 2026. Updated November 2, 2026. Branch: `feat/native-android`.

The owner connected the target phone over USB with debugging authorized. The development app and fixture-bearing test APK installed successfully. This extends the emulator evidence to a physical ARM64 device; full feasibility acceptance remains open.

## Device matrix

| Field | Observed value |
| --- | --- |
| Model | Samsung Galaxy S22 Ultra, SM-S908E |
| Android | 16, API 36 |
| SoC properties | QTI / SM8450 |
| App ABI | arm64-v8a |
| Memory reported by Android | 11,473,788 KiB, approximately 10.94 GiB |
| Available storage at setup | Approximately 43 GiB |
| Memory page size | 4096 bytes |
| Power during tests | USB connected; battery 88% |
| Battery temperature before/after short audio suite | 32.7°C / 32.7°C |
| Thermal status before/after short audio suite | 0 / 0 |
| App version | 0.1.0-dev, version code 1; debug signed |
| ChatGPT plan tier | Not yet recorded |
| Phone authorization and inference | Verified November 2, including inference after a full app process restart |
| Benchmark languages | English and Brazilian Portuguese planned; only public English speech tested so far |
| Install method | Development APK installed through USB ADB; personal-use debug build |
| Desired offline behavior | Planning default: playback and saved content remain useful offline; owner-specific preferences not yet recorded |

No device serial, account identity or credentials are included in this receipt.

## Executed audio checks

All **9 focused audio/native instrumentation tests pass**, in 12.403 seconds. [Runner output](evidence/s22-audio-tests.txt) records the executed suite.

- Real WAV decoding retains the PCM samples.
- Real MP3 decoding and generated stereo 44.1 kHz AAC decoding/resampling both produce recognizable speech with tiny.en.
- Malformed and oversized audio are rejected; cancellation discards partial decoded audio.
- Packaged ARM64 JNI produces timed segments from the public JFK speech fixture.
- An invalid model raises a Java exception; cancellation works before start and during an active native call. A fresh operation still succeeds afterward.

The standalone JNI call transcribed **11.0 seconds of speech in 1,605 ms**, including model loading inside the call. This is an observed real-time factor of approximately **0.146** for this sample/model/device, not a full-podcast performance claim. Active native cancellation returned in **1,140 ms**.

The suite used the pinned upstream public samples and the verified tiny.en model. Its models and decoded outputs use separate temporary cache files and are deleted by test cleanup. The normal imported clip and credential vault are not modified by this audio suite. The test writes a public-fixture transcript report in the app's files directory.

These short, USB-connected checks do not measure battery drain, sustained thermals, peak memory, Portuguese accuracy, full episodes or locked-screen transcription.

## Playback and account follow-up

The first playback/navigation attempt could not reach a resumed Compose Activity. Device inspection showed the phone was dozing with keyguard showing. After the owner unlocked the phone, both focused UI tests passed in **3.071 seconds**. [Runner output](evidence/s22-playback-tests.txt) records background playback after Home, media-session pause and seeking to the original 2,000 ms position, plus reuse of MainActivity on app reentry. This brings the executed physical-phone checks to **11 passing tests** across the two focused suites. The initial failure was an unmet screen-unlock precondition.

The first real phone authorization produced a permission email but no saved account connection; Chrome timed out at the loopback callback. The listener was hardened and its real browser callback and navigation regressions passed on the phone. See [callback correction and evidence](callback-validation.md). A fresh real authorization attempt has been started with the updated APK. The emulator's saved credentials are not copied to the phone. A completed response on this installation, representative English/Portuguese podcast tests and lifecycle/recovery checks remain required before closing the relevant feasibility tasks.

Installed development APK SHA-256: `e05e3989be2680be49c441eacc9e28fb6c63b91cbb0024c9e4ccc2cc1ec3fcc9`.

The callback correction upgraded the installed APK to SHA-256 `fb7fed58fa4446353fc88bb6f1a75cc836bcbc371f849886631bed657c6d44d1`.

The callback then completed, but a DNS failure prevented native token exchange. The [network recovery continuation](callback-validation.md#token-exchange-network-recovery) adds foreground completion and a controlled retry. Its three physical-device checks pass, including real native HTTPS access to public identity keys. The latest installed APK SHA-256 is `8abe72abd970238e921e6decf5b5b510bd85a0d824f40ddaf01431011aa9e91f`.

## Live account and restart verification — November 2

The owner completed browser sign-in, returned to DistillPod, entered an excerpt and ran the AI check. Direct inspection of the phone confirmed the saved connection and enabled inference action. The visible result panel contained a 69-character response; its contents and the owner's excerpt were not copied into repository evidence.

The app was then force-stopped and relaunched. **The saved account connection survived the process restart.** One further AI check used this public, non-sensitive validation input:

> Small daily routines help people build skills. Reviewing progress each week makes improvement easier to notice.

The restarted app discovered an entitled model and displayed a completed **179-character AI response**. The provider only returns text after a completed Responses stream, so an interrupted or incomplete stream would display a failure instead. This establishes live subscription-backed text inference on the physical ARM64 phone using its saved OAuth connection. No API key or fallback provider was used.

[Sanitized validation metadata](evidence/s22-live-inference.json) records the restart checks, response length and response hash. It contains no tokens, callback URLs, account identifiers, device serial, owner excerpt or private response text. The actual selected model slug was not recorded.

F03's real Android authorization requirement now has physical-device evidence alongside the callback cancellation/state/denial regression tests. F04 remains partially complete: live refresh-token rotation, denied plan permission on the live account and account-switch behavior have not been established by these two app launches. ChatGPT plan tier remains unspecified. Representative English/Portuguese podcast accuracy, sustained performance, locked-screen ASR and process-death job recovery remain open.
