# Gallery search acceptance repair (#102)

Main `ce94bce` failed [Android CI 42858095533](https://github.com/andrepaim/distillpod/actions/runs/42858095533): four startup cases passed, then ordinary108 had one failure in `DesignReviewDeviceTest.gallerySearchFindsShowsBeyondTheRail` at its first text input. The merged label selector found zero nodes while the unmerged tree contained one. Later acceptance phases did not run; aggregate correctly failed. Gallery/Shows/LocalSearch sources are unchanged from pre-PR101 `d303258`. This is an observed acceptance target/readiness failure; no production search regression or exact transient cause is established.

## Intended behavior and scope

Make the existing scenario interact with the visible editable field in the Shows grid after navigation. Retain input text, IME submission, show29 beyond the initial rail, exclusion of show00, navigation/back query retention, clear action and restoration of unfiltered results. Preserve label presence separately from the actionable target. A missing, duplicate, disabled or noneditable target and a search that does not filter must still fail.

Expected scope is Android acceptance only. No production code, runtime dependencies, data/schema, permissions, background work, signing, phone/network/security settings or release metadata changes. #60 remains parked/open until this main repair passes; the root session holds the integration lane.

## Steps before implementation

1. Retain the exact hosted red log/artifact and inspect the pinned Compose text-action implementation and current navigation/semantics path. `performTextInput` first fetches its selector and requests focus on that node; label decoration alone is not a durable editing identity.
2. Add a compact viewport variant of the real gallery scenario, without changing emulator/device settings. Run a bounded original/compact baseline; a local pass is a comparison sample, not a reproduction of the historical failure.
3. Use a unique editable-action selector scoped under `shows-grid`. Bound grid/field readiness, bring the search header into view and assert the target is displayed/enabled before editing. Preserve label existence separately. Do not input into a decorative unmerged label, add sleeps/blanket retries or weaken functional assertions. Keep timeouts finite and diagnose missing/duplicate targets.
4. Run the original and compact cases, the complete DesignReview class and affected Shows/Compose/picker acceptance on the owned emulator. Review exact-head diff and rerun relevant lint/APKs in default/voice configurations. Runtime and JVM-test inputs remain unchanged unless investigation supports a scope change.
5. Pass all selected full hosted checks on the actual final head, refresh against current main, merge normally without protection bypass and verify combined-main CI before closing #102 or resuming #60.

## Risk and evidence

The test-only correction changes how acceptance locates and waits for an existing control; it cannot establish a production UI cause. Appropriate emulator/full hosted evidence is sufficient, with no new physical-device requirement under the risk policy. Reassess if runtime scope changes. Use only the owned disposable emulator and synthetic rows/screenshots; do not install or run these fixtures on the owner phone. Serialize builds/emulator access with the shared locks, use the pinned native revision and `--max-workers=2`, and preserve unrelated worktrees.

Record exact revisions, failed/passed/not-run stages and limits in #102/PR evidence. Historical PR101 success does not replace failed merged-main validation. No release is authorized.

## Baseline and selected correction

The original and newly added 320×400dp gallery cases both passed once on the owned
API35 emulator at `a41ece9` (10.474 seconds). This is a comparison sample, not a
local reproduction or proof that the hosted failure was harmless. The exact hosted
red remains the supported failure evidence.

The selected correction waits for one Shows grid, scrolls its header into view,
then waits for one editable-action node within that grid and checks visibility
and enabled state. Label presence is checked separately in the unmerged tree;
text input/IME/query checks target the actual editor. All original search/result/
back/clear assertions remain, with additional filtered-count and empty-editor
checks. Timeouts retain their cause and report cardinality. Production sources
and ordinary JVM-test inputs remain unchanged.
