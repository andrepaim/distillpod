# Automatic show preparation

On a followed show's page, enable **Automatically prepare new episodes**. The first
successful refresh establishes a baseline; it does not process the back catalog.
Later refreshes admit newly discovered episodes, excluding episodes explicitly dated
before activation. Missing dates use discovery after the baseline. Disabling and
re-enabling establishes a fresh baseline. Unsubscribing through the app disables it.

Settings → Storage controls feed checking: 6 hours by default, presets or a custom
whole-hour interval from 1 to 168. Refresh subscriptions controls scheduled checks;
manual refresh still discovers episodes. Android can delay checks, and force-stop
prevents background execution until reopening the app.

Device-local Room records retain show intent and episode stages. WorkManager downloads
verified audio, existing resumable transcription prepares local/publisher text, and
existing analysis generates summaries, chapters, and suggested ads. Online cached text
is not used for automatic analysis. A coordinator advances completed stages. It runs
when the state it acts on changes: a job's stage, the show's automation and follow
state, an episode's download state, local file and audio fingerprint, the transcript
and analysis state and revision, and ChatGPT sign-in. Playback position, download
bytes and transcription chunk progress do not run it, and neither does moving between
queued and running within a stage (#140). Startup also reconciles stages.

Running work is serialized: a job with a queued or running download, transcript or
analysis holds later jobs back. A job that cannot continue without the user, such as
a paused or failed download (including Android's refusal of a background download,
#138), a paused, failed, partial or stale transcript, a missing model, or a failed,
paused or quota-limited analysis, is marked blocked and later episodes continue.
When its blocker clears (Resume, a downloaded model, a new transcript, sign-in or
Retry preparation), the coordinator runs again and the job resumes its stage.
Manual work can run independently. Network, model, charging and storage constraints
remain enforced by the existing components. Missing account access waits; failures,
user pauses and other blocked jobs show a retry action. Missing models can be
downloaded from Transcription settings.

Disabling automation removes its pending intent. An already dispatched shared worker
may finish its current operation; subsequent automatic stages stop. This avoids
canceling work shared with manual requests. Existing downloads and insights are kept.
Temporary download network failures retry up to three times with WorkManager backoff.
Other failures retain checkpoints and require explicit retry;
the coordinator retries its own transient scheduling failures at most three times.

The player shows **Skip marked ads** only with a complete current local/publisher
transcript, successful matching analysis, and an available local audio file. No ad
ranges produces **No ads marked**. First enabling selects suggested ranges for this
episode. Review marked ads allows removing incorrect ranges, and later off/on toggles
preserve those exclusions. The choice is stored against the analysis revision, so
new analysis requires opting in again. Existing global approved-ad settings remain
the fallback for episodes without an individual choice. Undo and intentional seeks
continue using the existing original-audio timeline behavior.

Automation settings, jobs, and episode skip preferences are local to this installation
and excluded from portable backups and cloud subscription sync. Database migration
9→10 adds separate tables without changing existing records or backup formats.

## Validation

Automated coverage includes feed baselines, failures/304 responses, duplicate
admission, old publication dates, disable/re-enable, persisted stage recovery,
manual pauses, authentication waits, per-episode ad choices, review exclusions,
stale analysis, interval persistence/validation, and upgrades from schemas 1–9.
Run the checks in [development](../development.md). Emulator and physical-device
background/battery behavior require separate evidence; no owner-device or live
account actions are part of this change.

## Device acceptance — November 8, 2026

Source revision `c613a42` (implementation `5f34344`) passed **14 tests on an
Android 16 x86_64 emulator** and **13 tests on the USB-connected Samsung S22 Ultra
(SM-S908E), Android 16**. Separate stage → force-stop → verify invocations each
passed on both devices. The [machine-readable receipt](evidence/show-automation-device-validation.json)
records APK hashes, counts, timings, coverage, cleanup and limitations.

Both devices used the separate `dev.distillpod.android.validation` application and
`dev.distillpod.android.validation.test` instrumentation packages. The owner's
installation was not replaced or accessed. Both temporary packages were removed;
the owner's application code path, version and update time remained unchanged.
No global phone settings were changed. Initial phone UI checks could not find a
Compose hierarchy while the phone was dozing behind its lock screen; after the
owner unlocked it, both UI tests and the final complete suite passed.

The feature test exercises a loopback feed, actual WorkManager download, publisher
transcript processing, injected fake-AI summary/chapters/ads, actual skipping of
silent fixture audio, disabling skipping, activity recreation, and interval updates.
Both architectures also decode an MP3 and recognize real public speech with local
Whisper. The emulator additionally exercises streaming playback with automatic
local transcription. Explicit process restart phases retain queued automation intent,
complete transcript and interval without sending a live AI request.

These checks do **not** establish six hours of unattended periodic execution,
deep-Doze delivery, unplugged battery consumption, live AI access, or release-signed
in-place migration of the owner's installation. The complete automatic workflow
used a publisher transcript; real local Whisper was validated separately.

For isolated reproduction, use a temporary Gradle init script:

```groovy
allprojects {
    afterEvaluate {
        if (path == ':app') {
            android.defaultConfig.applicationId = 'dev.distillpod.android.validation'
            android.defaultConfig.testApplicationId = 'dev.distillpod.android.validation.test'
        }
    }
}
```

Build `:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` with
`-I /path/to/validation.init.gradle` and
`-PdistillpodFixtureDir=/path/to/verified/fixtures`. Follow the development guide to
prepare fixtures. Install both APKs only after confirming these validation package
names are unused. Run `ShowAutomationDeviceTest`, `DatabaseUpgradeDeviceTest`, and
`AudioClipDecoderTest#mp3DecodesAndTranscribesRealSpeech` through `IsolatedTestRunner`.
The emulator-only additional case is
`AutomaticTranscriptionDeviceTest#streamingPlaybackDownloadsAndTranscribesLocallyWithoutReplacingTheActiveRevision`.

For a true process restart, invoke
`ShowAutomationDeviceTest#durableIntentSurvivesProcessRestart` with instrumentation
argument `automationPhase=stage`, force-stop **only** the validation app, then invoke
it with `automationPhase=verify`. Verify each log with `scripts/device_result.py`.
The normal suite runs this method as an activity-recreation check without a phase
argument. Physical UI testing requires the owner to unlock the device. Remove the
two validation packages afterward; never clear or uninstall the owner's package.
