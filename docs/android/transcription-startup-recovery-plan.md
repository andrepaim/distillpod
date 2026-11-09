# Foreground transcription startup recovery (#56)

Plan recorded before implementation at base 9528abf.

- Classify only Android foreground-start rejection at the foreground boundary as recoverable. Return typed outcomes to the worker, preserving permanent failure and cancellation semantics.
- Under the control lock, retain the current revision, segments, coverage, progress and source. Do not overwrite pause, replacement, or terminal states.
- Retry the existing constrained unique request with explicit exponential backoff (30 seconds minimum), at most five attempts. Exhaustion pauses with an actionable Resume message, using existing resume UI rather than an unknown persisted state. An explicit resume starts a fresh bounded attempt series.
- Test first: injected platform rejection must preserve state, map to retry, recover successfully, and stop after its budget. Test pause, supersession, cancellation and permanent errors separately.
- Run relevant unit tests, core tests, lint and debug assembly, then applicable emulator acceptance. Serialize builds with the shared build lock.
- High platform/background-execution risk: physical Android validation is mandatory, after coordinator approves device protocol. Preserve signing identity and all production data; no uninstall/clear-data or destructive fixtures. Record exact device/OS/revision and unavailable evidence. Never claim synthetic injection establishes OEM rejection conditions.
- No release changes. Draft PR remains unmerged until required device evidence and combined-main review/CI.

## Isolated device protocol

Use scripts/startup56-validation.init.gradle only for the opt-in validation build.
It retains DistillPodApplication and the real graph/services, assigns application
ID dev.distillpod.android.validation.startup56 and test ID
dev.distillpod.android.validation.startup56.test, rejects Sentry/Firebase
configuration, and removes INTERNET from both packages.

Build assembleDebug and assembleDebugAndroidTest with that init script. Verify
both merged APK package identities, instrumentation target, lack of INTERNET,
and APK hashes before installation. Confirm both packages are unused on the
selected device. Acquire the coordinated exclusive device slot before any
installation, activity launch, or instrumentation.

Run only dev.distillpod.android.TranscriptionStartupDeviceTest through
IsolatedTestRunner. The test refuses any other target package and requires API
31+. Fixtures use unique synthetic episodes and checkpoints, no accounts,
downloaded models, personal media, or network. Cases cover injected startup
rejection and successful repository completion, repeated wrapped rejection and
budget exhaustion, newer control state protection, and actual MainActivity
resume starting the real foreground service to the safe missing-model state.
The last case backgrounds/resumes the activity and verifies a user pause remains
paused. Remove only the dedicated packages created for this probe afterward.

These checks do not force an OEM denial, modify power/permission/security settings,
or establish long-duration scheduling, model inference, or production upgrade
compatibility. Record those limits separately from observed recovery results.
A controlled injected rejection is not evidence that the historical OEM trigger
has been reproduced. Broad emulator acceptance remains separate.
