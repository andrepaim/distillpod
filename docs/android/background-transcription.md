# Transcription with the screen locked

> Historical validation report. Results and commands below apply to the stated
> revision and device. For current behavior, use the [usage guide](../getting-started.md),
> [build instructions](../development.md) and [implementation status](implementation-status.md).

November 4, 2026.

The reported symptom was preparation stopping after the phone locked, with another manual start required. The old implementation already promoted each WorkManager worker to a foreground service, but finished that worker after every two-minute audio window and scheduled another job for the next window. That left execution dependent on another Android scheduling decision between checkpoints. Android 16 also applies JobScheduler runtime quotas to foreground WorkManager jobs. The attached S22 reports API 36. Historical logs from the reported run are unavailable, so this identifies a code-level failure path rather than proving which OS stop reason occurred yesterday.

User-started preparation now starts a dedicated, non-exported foreground service. One session consumes successive windows without scheduling another job between them. Native inference remains on the existing single executor; the service does not run inference on the main thread. Its bounded partial wake lock permits CPU work with the display off. The lock and notification are released when the session stops. The notification can pause the current episode; cancellation reaches both decoding and native Whisper.

The existing two-minute Room checkpoints remain authoritative. Resume keeps the same transcript revision and skips completed coverage; live percentages are never treated as saved text. A sticky restart or returning to the app can pick up queued work. Explicitly paused rows stay paused. A six-hour session limit and Android's foreground-service timeout preserve checkpoints and expose a resumable pause.

Battery, charging-only and network policies still apply. Downloaded audio can be transcribed offline. When a requirement is unmet, deferred WorkManager requests retain Android's constraints. Background work that cannot legally start a direct foreground service also retains that fallback. These deferred requests remain subject to Android scheduling; the direct service is used for the listening-session path initiated while the app is visible. Force-stop and user/system restrictions can still interrupt work.

Android 15+ local inference uses `mediaProcessing` alone, avoiding consumption of the unrelated `dataSync` allowance. Older Android versions use the existing `dataSync` declaration. This does not use a playback or microphone service type for computation. The platform `startForeground` overload is used directly: the AndroidX Core version currently resolved by this project filters out the API 35 media-processing bit. The first emulator trial exposed that incompatibility; an API 35 unit regression now verifies the actual foreground-service type.

## Comparison with existing projects

Reviewed primary source on November 4, 2026. Repository stars are only an indication of public adoption, not proof of reliability. No external implementation was copied.

| Project | Relevant implementation | What applies to DistillPod |
| --- | --- | --- |
| [FUTO Voice Input](https://github.com/futo-org/voice-input) (~350 stars) | [AudioRecognizer](https://github.com/futo-org/voice-input/blob/master/shared/src/main/java/org/futo/voiceinput/shared/AudioRecognizer.kt) runs model work on `Dispatchers.Default` in a lifecycle scope, with explicit cancellation; its main use is short dictation through an activity or input method. | Background execution and native cancellation are appropriate. Its dictation lifecycle is not evidence that an hour-long file should be tied to an activity or keyboard. |
| [Transcribro](https://github.com/soupslurpr/Transcribro) (~760 stars) | [MainRecognitionService](https://github.com/soupslurpr/Transcribro/blob/main/app/src/main/kotlin/dev/soupslurpr/transcribro/recognitionservice/MainRecognitionService.kt) owns IO coroutine jobs and cancels/releases recognition on service destruction. | Service ownership and deliberate cancellation are common. It is an Android recognition service, not a directly comparable long-file foreground-service implementation. |
| [VoiceSkip](https://github.com/tguillem/VoiceSkip) (smaller project, 8 stars) | [TranscriptionService](https://github.com/tguillem/VoiceSkip/blob/main/app/src/main/java/com/voiceskip/service/TranscriptionService.kt) is a foreground service for audio files, uses a wake-lock manager, a notification/cancel action, and timeout/destruction cleanup. | This is the closest use-case match and supports the dedicated service + CPU wake lock pattern. DistillPod retains its own durable checkpoints and uses the newer media-processing type where supported. |

The authoritative Android references are [long-running WorkManager jobs and Android 16 quotas](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running), [foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types#media-processing), [foreground-service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout), and [wake-lock lifecycle](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock). The direct foreground-service alternative is explicitly described in the WorkManager guidance for Android 16 quota exhaustion.

## Regression checks

`TranscriptionServiceTest` verifies that a session holds and releases its CPU wake lock, that local work is allowed offline, and that charging/low-battery/network requirements are enforced.

`NativePipelineDeviceTest` uses a public-speech fixture on an isolated emulator. It verifies progress before the first checkpoint, pauses after the first saved window, resumes the same revision, removes all scheduled jobs for that transcript, sends the app home, and turns the display off. Both remaining windows must finish before the screen is woken. Saved coverage must be exactly 0–120, 120–240 and 240–330 seconds with real recognized speech.

The targeted API 36.1 emulator regression passed in 58.7 seconds. All 48 core and 86 app JVM tests pass, including the wake-lock, service-type and power-policy checks. All 30 ordinary emulator acceptance tests passed in 160.1 seconds. A final four-test regression passed in 68.8 seconds after adding a real scheduled-recovery handoff check: a worker retains its retry while the service owns inference, then the test cancels all scheduled transcript jobs before locking the display. Debug/release lint, APK builds, 11 release-tool tests, alignment and retained-certificate verification pass. See [validation evidence](evidence/background-transcription-validation.json).

Validation also exposed an existing backup-operation race: earlier temporary-file cleanup could mark a newer operation interrupted. Cleanup is now guarded by its operation identity; the existing recovery and corrupted-provider regressions pass. The screen-off test does not simulate an exhausted Android runtime quota or establish the exact OS stop reason from the reported run. No fixture suite or live-account request was run on the owner's phone.
