# Shows reset acceptance

Follow-up to #77, tracked in #83. Plan: diagnose the failed reset/grid lookup,
produce a controlled failing case, preserve the existing assertions, make the
smallest supported correction, and validate the combined code through PR and
merged-main CI.

## Evidence and correction

Main `7fd54fa` failed the Shows reset test in run `42706025539`. Compose 1.7.8
failed while printing a failed grid lookup's diagnostic on the instrumentation
thread. That diagnostic obscures the original lookup failure; it does not prove
a production UI regression or establish the exact historical window state.

The normal API 35 baseline and twelve keyboard-close/reset cycles passed. A
controlled 400dp-high app host then exposed a test interaction defect: the test
tapped Reset filters without scrolling it into view. The tap did not clear the
search. The route remained Shows, the grid still existed, All was already
selected, and the library still contained four shows, but scrolling could not
find the empty feed while the unmatched search remained active. Thus checking
All alone did not establish that reset had happened.

Scroll the reset button into view and assert that it is displayed before
tapping it. Retain all existing assertions and additionally check that reset
keeps the Shows route and clears the empty-result state. A new short-host test
repeats that interaction three times, then checks that the empty feed remains
reachable. Failure diagnostics retain the route, grid count, filter and show
count without suppressing the original failure. No sleeps or test retries are
introduced.

The short-host test failed before this correction and passed afterward, along
with both original Shows cases, on isolated API 35 at 320×640/160dpi. The precise
trigger of the historical CI grid disappearance remains unobserved; the
correction addresses a demonstrated invalid interaction in the same test path.

## Risk and completion gates

Test-only change: no production code, runtime dependencies, schema, preferences,
hardware, permissions or release metadata changes. Emulator acceptance and
automated build/lint checks cover the affected test behavior; a physical phone
is unnecessary for this scope. Synthetic data only; no owner device or account.

Run both original Shows cases, the new controlled case and affected Compose
regressions; review the diff and integration with current main. All required PR
checks and successful CI on the merged main commit remain completion gates.
Record exact revisions and results on #83 and the PR.
