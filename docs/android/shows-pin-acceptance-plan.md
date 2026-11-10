# Shows unpin acceptance (#107)

Base: main `531810f34dcef9a8e6932b84d01e964b17838803`.
The Shows owner confirmed no active ownership conflict. Its completed #77/#83
worktrees remain untouched; this session uses `distillpod-issue107` and
`test/107-shows-pin-acceptance`. #89 is open, parked and unassigned.

## Evidence and corrected diagnosis

[Run 42897106331](https://github.com/andrepaim/distillpod/actions/runs/42897106331/job/130113083781)
passed startup4 and ordinary121/122. The failure is Shows line127,
`awaitPin(1,false)` after an unpin click, through `scrollCard` and
`performScrollToNode` into Compose's semantics dump. Later phases did not run.
Transcript and queue cases passed. No user-facing pin failure or cache cause is
established, and no unchanged-source retry has been performed.

Pinned Compose1.7.8 source corrects the initial missing-grid hypothesis:
`fetchOneOrThrow` line189 unconditionally calls `printToString` after verifying
exactly one match. The observed dump therefore started with a uniquely matched
grid. Even depth-zero output traverses children/siblings, causing the observed
SnapshotStateObserver conflict on the instrumentation thread. Shows StateFlow
does not ordinarily reset to null on a pin update. #83's Reset fix remains valid;
this failure happened before Reset and requires a separate correction.

## Test-first correction

1. Preserve raw evidence privately. Read the pinned APIs and retain original
   failure causes; report cardinality/route/preferences without dumping a tree
   from the instrumentation thread.
2. Add a small test interaction using the public keyed-grid scroll semantics.
   First demonstrate behavioral red for its main-thread lookup/action contract
   with real Compose semantics, then implement collection selection (no
   `fetchOneOrThrow` dump), unique-grid validation, and Android-main property,
   bounds and action access. Fail closed for unavailable grids/keys/actions.
   The contract test does not claim unique reproduction of the historical
   simultaneous observer schedule.
3. Keep actual scrolling and every persisted pin, icon, visual order, format,
   recreation, query, Reset, route and empty-feed assertion. Exercise the real
   pin/unpin sequence in a compact viewport and retain large-font coverage.
   No sleeps, blanket retries, caught-and-ignored failures, dependency patches
   or disabling SnapshotStateObserver. Change runtime only with separate evidence
   and an updated risk assessment.

## Risk and completion

Expected scope is Android test interaction and regression cases only. Review
runtime/core/JVM/build/script equivalence with the base. Suitable automated and
real emulator evidence is sufficient; no phone testing of emulator-only fixtures.
Run relevant default/voice lint/APK compilation, affected Shows/Compose/gallery
emulator checks and all seven full hosted checks on the exact final head.
Refresh/integrate main, review, merge normally and verify selected merged-main
plus independent security before closing. Keep #89 open for successful corrected
warm acceptance; never count the failed run as a timing improvement. No release,
owner data, credentials or phone/network/security settings changes.

Primary pinned source: [Compose1.7.8 source archive](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-test/1.7.8/ui-test-1.7.8-sources.jar).
