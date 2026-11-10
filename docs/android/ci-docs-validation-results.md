# Documentation PR validation results (#88)

The routing implementation in [PR #94](https://github.com/andrepaim/distillpod/pull/94)
was merged as `3486684c94174070314465fe0755789fade61042`. Its
[plan](ci-docs-validation-plan.md) and the
[development guide](../development.md) describe the
validation contract. This report is the documentation-only follow-up used to
exercise that contract through a real pull-request event.

## Hosted measurements

Measurements below are single successful attempts on GitHub-hosted runners on
2026-11-10 UTC. Elapsed time runs from workflow start to final update. Summed job
time is the sum of job start/completion intervals, including overlapping jobs;
it is an execution proxy, not billed runner minutes. The timing API reported
zero billable duration despite nonzero job intervals, so billing savings cannot
be established from these reads. Run/job reads were bounded to 100 jobs per run;
the returned totals fit within that limit.

| Sample | Source revision | Elapsed | Executed jobs | Summed job time |
| --- | --- | --- | --- | --- |
| Previous full PR validation | `4184226452687d754f59f8be155cafd872e31cb8` | 15m07s Android; 13s separate security | 5 across both runs | 28m53s |
| New full PR validation | `982c67b6a55ff930c96aab0a0d61adfd0debc7c4` | 13m00s, including security | 7 | 25m18s |
| Documentation-only PR validation | Pending hosted evidence | Pending | Pending | Pending |

Baseline evidence: [Android run](https://github.com/andrepaim/distillpod/actions/runs/42806721375)
and [security run](https://github.com/andrepaim/distillpod/actions/runs/42806721187),
both attempt 1. New full-route evidence:
[run 42816323475](https://github.com/andrepaim/distillpod/actions/runs/42816323475),
attempt 1. All selected jobs passed. The classifier inspected eight changed
paths and selected full validation. The aggregate gate passed after its selected
dependencies succeeded.

The baseline and new full samples have identical `app/`, `core/` and `firebase/`
Git trees. The new run includes changed CI/Python guards and two additional jobs;
these are not identical total workloads. Host scheduling, caches and ordinary
run variation are uncontrolled. The observed full-run difference does not prove
a causal speedup, a typical duration, a flake rate or a billing reduction.

The documentation-only row must be completed from the hosted PR before this
report merges. A local isolated clone selected documentation validation for one
Markdown change, but that probe does not establish hosted required-check behavior.

## Validation contract and limits

The real full run preserved the Android default/voice builds, Firebase rules,
native bridge and complete isolated emulator acceptance. Their job step blocks,
the security scanner and the release workflow were compared with the prior
source and were unchanged. Locally, all 111 Python tests, workflow syntax,
incremental documentation links, whitespace and repository security guards passed.
Routing/gate regressions cover mixed and unknown paths, renames/deletions, more
than 300 files, incomplete Git evidence and failed/cancelled/unexpectedly skipped
selected jobs. These deterministic failures are local tests, not deliberately
failed hosted workflows.

For a documentation-only PR, the expected executed jobs are selection/documentation,
repository security and the aggregate `Kotlin, lint and APK` gate. Android build,
Firebase, native and emulator jobs are intentionally omitted. The gate requires
the exact checkout, successful documentation/security validation and only those
planned omissions. Existing required context names and branch protection remain
unchanged; no workflow-level PR path filter is used.

Unknown paths, mixed changes and manual/reusable invocations retain full
validation. Main pushes retain the existing native path filter and its documented
300-file limit; standalone security still runs on documentation-only main pushes.
External links are not fetched, and the local-link checker supports conventional
Markdown/HTML destinations rather than claiming complete CommonMark rendering.

This increment changes CI routing and documentation only. It does not change app
runtime, signing, persistence or upgrade behavior. No physical-device check or
local Android rebuild is required for this report; the implementation's full
hosted Android/emulator evidence is recorded above. Release publication remains
separate.
