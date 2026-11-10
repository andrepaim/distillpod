# Notification acceptance tap recurrence (#67)

Base: main `3486684c94174070314465fe0755789fade61042`.
The previously fixed readiness problem recurred in
[run 42819098246](https://github.com/andrepaim/distillpod/actions/runs/42819098246):
Allow readiness passed, then the original ten-second dismissal check failed with
permission and notifications disabled. Earlier recovery/revocation phases passed;
later cloud/voice acceptance was not reached. No production defect or GPU cause
is established. #88 remains open and its report PR #95 remains unmerged.

## Source findings and revised implementation plan

Pinned UiAutomator 2.3.0 `UiObject2.click()` uses `GestureController`, which injects
input asynchronously and discards injection results. This is a confirmed
acknowledgement gap; it is not proof of the historical failure's cause.
The same library's public `UiDevice.click(x,y)` uses synchronous injection and
returns the down/up result. Use that existing public API rather than introducing
custom gesture generation/cleanup. Retain one real UI tap and reject a false
result explicitly. No tap retries, shell grant or accessibility activation.

1. Preserve the failing hosted scenario/log as the red regression. Review the
   exact pinned source and public API. Do not invent a mock regression that
   simply restates an acknowledgement boolean check.
2. Retain existing owner, enabled/clickable, stable geometry and fresh lookup
   checks. Change only the permission button tap to the acknowledged public API.
   Keep all permission/notification/dialog/activity/UI postconditions and bounds.
3. Record bounded content-free target/window state immediately before input and
   on failure, including the acknowledgement result. Never dump UI text, window
   titles or screenshots. Preserve the original exception if diagnostics fail.
4. Compile/lint/build default and voice test configurations; run the actual
   affected permission scenarios on the verified disposable API35 emulator.
   Keep original failed evidence. Passing cases validate this revision, but do
   not prove why a previous asynchronous tap was ineffective.
5. Require complete hosted default/startup/ordinary/cloud/voice acceptance and
   other selected checks on the exact head. Review/integrate current main, merge
   normally and verify combined-main CI. Diagnose new failures rather than retry
   unchanged code. Resume #88 only after its dependency is satisfied.

## Risk and evidence

Test fixture only: no production permission, app runtime, signing, upgrade or
persistence changes. No physical phone action is applicable. Local instrumentation
compilation/lint, disposable emulator acceptance and full hosted checks establish
fixture behavior; actual production/OEM permission validation is not claimed.
No settings, protection, credentials, release or backend changes.

## Validation-driven scope update

The first voice gate passed 99 core and 576 app tests, lint and both APKs. The
owned API35 emulator passed all three permission phases. The default JVM gate
then failed waiting for `DownloadNetworkPolicyTest`'s application-graph shutdown
before its policy assertions (one of 576 app cases failed). Preserve that red
log/XML; no specific stuck child was captured, so its exact cause is unproven.

That fixture starts uncontrolled application recovery only to cancel it before
setup. Use the existing pre-cancelled-scope construction pattern from
`AutomaticTranscriptionTest` and `TranscriptParentLifetimeTest`, so those unwanted
startup jobs never begin. Retain every policy assertion and timeout. This is a
second test-fixture correction required by validation, with no product change.
Revalidate both complete local configurations and hosted checks on the combined
final head. Earlier results remain scoped to their original revision.

The initial isolated graph was incomplete: `AppJobs` resolves `context.appGraph`
internally. A new identity assertion deterministically failed because it saw a
second graph. Bind the application's lazy graph delegate to the isolated graph
using Robolectric reflection in this JVM fixture, then retain that identity
assertion. This introduces no production test hook. The complete network-policy
test class passed after that wiring correction; full final-head gates remain
required. The earlier failed intermediate suite is preserved separately.

The next complete voice suite passed all policy cases but failed
`ContextSummaryOverloadTest` at its first five-second diagnostics executor barrier.
Robolectric reported queued main work; no executor stack was captured at failure.
A new controlled callback test reproduced how the blocking fixture wait prevents
main-looper progress. Await the same barrier off-main while advancing queued main
work, with a five-second outer bound and interruptible cancellation. Both that
regression and the real diagnostics flood test passed in the focused class run.
Keep the original event counts, budget/privacy/journal assertions, production
diagnostics implementation and barrier timeout. This establishes fixture progress
behavior without claiming a proven cause for the earlier historical timeout.
Full combined default/voice and hosted validation are still required.

References: [UiDevice API](https://developer.android.com/reference/androidx/test/uiautomator/UiDevice),
[platform input contract](https://developer.android.com/reference/android/app/UiAutomation#injectInputEvent(android.view.InputEvent,boolean)).
The exact 2.3.0 source was read from Google's Maven sources artifact, rather than
assuming current AndroidX implementation details match the pinned dependency.
