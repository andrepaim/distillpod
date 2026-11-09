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

## Review-driven scheduling fix

A deterministic WorkManager database regression reproduced Resume racing the
exhausted worker's return: KEEP retained its RUNNING request after the row had
already become paused. Explicit Resume now awaits cancellation of only that
revision's tagged requests before publishing queued and dispatching. The test
requires a fresh request with zero attempts, unchanged charging/network/battery
constraints, exponential 30-second backoff, preserved checkpoint/revision, and
another revision left untouched. Cancellation waits for the WorkManager
operation, not for the old worker to join while the control lock is held.

The dedicated hosted emulator invocation builds the same offline package in
the existing disposable emulator job, requires all four startup cases, refuses
physical targets and existing validation packages, verifies manifest identities
and no INTERNET permission, and cleans up only packages it installed. The
ordinary emulator suite is unchanged.

A second deterministic regression holds a foreground failure past cancellation,
pauses then explicitly resumes the same revision, and releases the late rejected
future at its retry limit. It reproduced an unwanted second pause. Foreground
error classification now checks coroutine cancellation first, so a cancelled old
attempt cannot apply its exhausted outcome to the resumed revision.

Review additionally staged cancellation after foreground classification while
recovery waited for the control mutex. That regression also reproduced an
unwanted second pause. Cleanup now consults the captured original execution Job
inside the same control lock as the persistence decision. It never consults the
NonCancellable cleanup Job for this decision, and cancellation propagates after
cleanup. This covers both sides of the classification-to-mutation boundary.

## Resume lifecycle regression found during hosted acceptance

At f552525 the first three startup device cases passed; the real-activity case
remained queued with the same revision, no network requirement or power/network
wait, and no running service. Its original 15-second bound and assertions remain.

The one-shot onResume guard could run before the RESUMED lifecycle event when
graph initialization had already completed. Extracting that exact guard reproduced
two deterministic failures: an already-ready graph did not recover after resume,
and initialization completed in the background did not recover on the next resume.
Register one lifecycle observer during activity creation and use repeatOnLifecycle
at RESUMED to await readiness and recover once per active interval. Leaving that
state cancels pending initialization/recovery; destruction cannot start it later.
All three lifecycle tests passed after the change. Existing bounded retry and
cancellation/replacement race behavior is unchanged.

Rerun full default/voice gates and all hosted acceptance on the final commit,
including the real-activity case. Rebuild/verify the offline startup APK pair and
run the isolated S22 protocol when transport is available. The lifecycle tests do
not establish historical OEM denial conditions or long-duration scheduling.
