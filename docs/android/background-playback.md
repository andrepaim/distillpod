# Background playback validation

Target: owner's S22 Ultra, Android 16. The owner authorized a connected, unused hour. No Bluetooth headset or speaker is available, so physical Bluetooth disconnect/reconnect remains open.

## Screen-off fix

The pinned Media3 1.5.1 player now explicitly enables `C.WAKE_MODE_LOCAL`; the manifest declares `WAKE_LOCK`. This lets ExoPlayer hold its CPU wake lock during playback. The existing foreground media service, audio-focus handling and becoming-noisy handling remain in use. See [Android's background playback troubleshooting](https://developer.android.com/media/media3/exoplayer/troubleshooting#how-do-i-keep-audio-playing-when-my-app-is-backgrounded).

## Hour protocol

`PlaybackReliabilityDeviceTest#stageLockedTrial`, explicitly selected with `-e lockedTrial stage`, downloads a real public episode over an unmetered connection. It checks the downloaded audio duration exceeds 61 minutes, preserves the owner's queue/current position/speed/volume in a private file, and stages the isolated episode paused at 1×. The fixture is unsubscribed and does not use the owner's account or AI provider.

After staging instrumentation exits, `android/scripts/locked-playback-trial.py` launches the normal app, waits for its media session, starts playback through the targeted system session controller, disables USB keep-awake and turns the screen off. Global Play dispatch can arrive before session restoration and fail to select a session; setup must confirm actual playback before counting time. Samples from before final setup are excluded. The device remains USB connected and music volume is muted. No instrumentation remains active during the hour.

An explicitly enabled, debug-only recorder samples actual player position, state, errors, underruns, buffering, screen/keyguard state and thermal status every five seconds. It records only the fixed validation episode, omits owner titles/IDs/URLs, writes at most 1 MiB privately, and pauses the fixture after 65 minutes if unattended. It acquires no additional wake lock. The host requires at least 3,600,000 ms between locked, playing samples, no unexplained stop/rebuffer/error/underrun, sample gaps no greater than 15 seconds, and position advancement within two seconds of elapsed time.

After the hour, short instrumentation phases exercise synthetic transient/permanent audio-focus loss and force-stop/reopen with queue/position/speed restoration. These are separate from the elapsed hour and do not substitute for real Bluetooth or phone-call checks.

The host restores USB keep-awake and music volume in cleanup. Explicit restoration instrumentation reinstates the owner's queue/current position/speed/volume paused, verifies persisted queue/speed, removes the fixture audio/rows and disables recording. Credentials and other library data are untouched. The private queue backup remains recoverable if cleanup fails; do not clear app data to recover it.

This muted USB trial measures playback continuity and thermal status. It does not establish audible sound quality, unplugged battery drain, deep Doze behavior, Bluetooth handling, real phone-call interruptions or multi-hour transcription contention. Those acceptance items require separate evidence.

## Build checks

The wake-lock/recorder build passed 48 core and 56 app JVM tests, Android lint and main/test APK assembly. At the time of the hour trial, the installed APK checksum matched the [playback build receipt](evidence/background-playback-build.json). The subsequent [0.3.0 UI revision](ui-improvement-proposal.md) retains this service behavior and has its own install receipt; the hour was not repeated for a UI-only revision.

## Physical result — November 2, 2026

The [hour receipt](evidence/s22-locked-playback.json) passes: **60 minutes 16.841 seconds**, 723 samples, zero player errors, zero underruns and no rebuffering. All measured samples were playing at 1× with the screen off and keyguard locked. Position advanced 3,616,845 ms against 3,616,841 ms elapsed, a final difference of 4 ms. [Raw samples](evidence/s22-locked-playback-samples.jsonl) permit independent verification.

The downloaded public source was 5,297,893 ms long and 90,223,039 bytes. Android's foreground-service state and `ExoPlayer:WakeLockManager` partial wake lock were observed during ordinary playback. An initial setup attempt that did not start playback was discarded; the diagnostic log was reset before the measured interval.

Thermal status briefly reached **1 (light)**, then returned to 0; playback continued without errors or underruns. Android defines light status as [light throttling without user-experience impact](https://developer.android.com/reference/android/os/PowerManager#THERMAL_STATUS_LIGHT). A separate battery snapshot was 33.8°C at 100% with USB power. This is not a measurement of unplugged battery drain.

The subsequent [audio-focus check](evidence/s22-background-controls.txt) passed transient interruption/resume and permanent loss without unexpected restart. The [force-stop/reopen check](evidence/s22-background-reopen.txt) restored the fixture queue, 12,345 ms position and 1.25× speed paused. These focus requests came from a synthetic competing client on the phone; real Bluetooth and telephone-call behavior remain untested.

[Owner restoration](evidence/s22-background-restore.txt) passed. Cleanup reinstated the owner's queue/current position/speed/volume paused, restored USB keep-awake and music volume, removed the fixture audio/rows and disabled recording. On November 3, [cleanup verification](evidence/s22-background-cleanup.json) confirmed the recovery flag/backup and fixture audio were absent, removed remaining diagnostic logs and the test package, and verified the main APK checksum again. Owner library and credentials were retained.
