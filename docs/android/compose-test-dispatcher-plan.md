# Android acceptance UI scheduling (#74)

Base: current main `a7422e9`. Main run 42657220484 failed in
ListeningDesignDeviceTest with SnapshotStateObserver on the instrumentation
thread. PR #61 run 42657325952 failed creating a backup AlertDialog/Handler
on DefaultDispatcher-worker-2 through TestMonotonicFrameClock/Recomposer.
These are confirmed test execution failures, not established production UI bugs.

The official Compose ui-test-android 1.7.8 sources select
UnconfinedTestDispatcher when effectContext does not supply a TestDispatcher.
The pinned createComposeRule/createAndroidComposeRule APIs accept effectContext;
no dependency upgrade is needed to test a queued dispatcher. Source archive:
https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-test-android/1.7.8/ui-test-android-1.7.8-sources.jar

1. Add a deterministic Robolectric regression using the actual Compose rule:
   suspend a LaunchedEffect on a deferred signal, complete it on Dispatchers.Default,
   and require the resumed UI effect to run on Android's main looper. Run the
   existing default rule first and record the expected red result.
2. Verify the supported StandardTestDispatcher effectContext makes that same
   regression pass. Apply the diagnosed scheduling configuration to the affected
   Android acceptance rules, preserving assertions, failure evidence, timeouts,
   picker/restore behavior and navigation/queue scenarios. Review whether any
   additional rules share the same demonstrated risk before broadening scope.
3. Compile/lint/build default and voice test APKs, run relevant local regressions
   and security/data checks, and require all five hosted checks on the actual
   final commit. A skipped phase is not a pass. Merge normally and verify current
   combined-main CI before closure. Record other failures separately; #75 tracks
   the cloud-queue mismatch and must not be waived to get green.

This changes test scheduling only: no production UI, persistence, dispatcher,
schema, dependencies, release metadata or phone security changes. The affected
disposable-emulator backup/listening fixtures must not run on the owner phone.
Existing feature physical acceptance gates remain. A passing targeted regression
does not alone prove the cause of every historical SnapshotStateObserver crash.

The legacy-rule regression failed with expected-main=true, actual=false, also
after explicit test-clock pumping. A bare StandardTestDispatcher leaves initial
effects queued in the pinned idle implementation. Use a shared test-only factory
that registers a main-looper scheduler drain before Compose's own idling resource;
then its ordinary frame/layout checks observe the queued work. Drain only current
tasks and preserve manual-clock control; do not advance virtual deadlines or
replace idle/acceptance assertions. Test it in both source sets. Robolectric's
specific strategy does not query custom idlers, so its regression explicitly
pumps the main clock; two guarded Android cases must verify the actual idler,
background completion and dialog show/dismiss without that manual pumping.

Local TDD evidence: the original rule failed the exact main-thread assertion
twice, with and without explicit clock pumping (expected true, observed false).
The queued shared factory passed that same regression with clock pumping and
compiled all Android tests. Bare queued configuration and the first Robolectric
custom-idler attempt failed their effect-start precondition; neither is counted
as green. Actual Android idler integration and full-suite validation remain
pending. No original acceptance assertion, bound or diagnostic was removed.

The first hosted implementation passed both new Android cases but failed the
three affected fixture cases during teardown: Compose's final runTest drained
queued lifecycle observer removal on the instrumentation thread. Wrap the
supported ActivityScenarioRule launcher so its existing activity close happens
first, then drain current cleanup tasks on main before returning to Compose's
coroutine validation. Preserve a body failure and attach cleanup failures rather
than replacing either with success. Add an explicit lifecycle collection and
activity-close Android regression.

A fresh disposable local API35 emulator passed the three focused regressions.
The original fixtures then revealed two distinct test assumptions: the show
label also occurs in clickable cards, and UiAutomator does not drain Compose's
queued picker-launch effect. Match the non-clickable show heading and wait for
Compose idle at the existing picker boundary. Keep the displayed-heading,
navigation/playback/queue, picker/grant/history/restore assertions and bounds.
All six cases subsequently passed together: three regressions, listening flow,
and both backup flows. These local working-tree results do not replace the
required final-commit full hosted and merged-main gates.
