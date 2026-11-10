# Android CI failure and retry audit, 2026-11-10

Report for [issue #91](https://github.com/andrepaim/distillpod/issues/91).
Snapshot main: `02f124b9535602a9e3914fdaa84b8f84267ca5a2`.
Report integrated with subsequently validated documentation-only main
`1061d3eeaa4cb7f6c4d208d3c12dd7d5115bc92e` (#92 / PR #98); the
audit cutoff and historical measurements remain unchanged.
This is read-only analysis and documentation, not a runtime change or a retry.

## Scope and reproducibility

- Workflow: `andrepaim/distillpod/.github/workflows/ci.yml` (Android CI), all
  branches and events, selected by run creation time in the half-open UTC window
  **2026-11-09 03:00:00 to 2026-11-10 14:54:00**.
- Baseline: November 9 03:00 through November 10 03:00 UTC, matching the original
  ticket's Sao Paulo calendar day. Follow-up: November 10 03:00 to 14:54 UTC.
- Read two newest REST run pages of 100 each using the owner's authenticated
  GitHub CLI in WSL Ubuntu-24.04. Page two reached November 8 13:47:54 UTC, older
  than the window start. Filter by `created_at`, deduplicate run IDs and retain
  the original run metadata, including source SHA, event and attempt count.
- Selected 116 runs. For each, read every attempt from 1 through `run_attempt`
  using the attempt jobs endpoint with `per_page=100`: 121 reads and 503 jobs.
  Every response's returned job count equaled `total_count`; no job page was
  truncated. Read all 37 failed-job logs and one cancelled-job log from each of
  the 16 latest-cancelled runs. All these reads succeeded.
- Read individual attempt metadata for the five rerun runs, and the timing
  endpoint for representative full-main run 42828876719. Issue closure evidence
  was checked for #67, #69, #72, #74, #75, #81, #83, #86 and #88. No artifacts,
  local test results, other workflows or owner activity were added to counts.
- Raw API responses/logs are private local audit evidence, not repository files.
  This report contains only sanitized build/test summaries and public links.
  The moving newest-page query is a bounded snapshot, not a future monitor.

To reproduce, use `gh api` with the following endpoint sequence, then apply the
explicit UTC filter above. For a later collection, continue run pagination until
crossing the window start rather than assuming two pages still suffice:

1. `repos/andrepaim/distillpod/actions/workflows/ci.yml/runs?per_page=100&page=N`
2. `repos/andrepaim/distillpod/actions/runs/RUN/attempts/ATTEMPT/jobs?per_page=100`
3. `repos/andrepaim/distillpod/actions/runs/RUN/attempts/ATTEMPT` for historical
   attempts whose outcome is replaced by the run's latest outcome.
4. `repos/andrepaim/distillpod/actions/jobs/JOB/logs` for failed jobs and the stated
   cancellation sample; `repos/andrepaim/distillpod/actions/runs/RUN/timing` for
   billing availability. Check pagination and errors before using results.

The formulas below are sufficient to reproduce the numeric analysis; no new
analysis program, workflow, dependency or production instrumentation is shipped.

## Outcomes, attempts and elapsed time

These are latest workflow outcomes, not unique bugs or flaky-test probabilities.

| Window | Runs | Success | Failure | Cancelled | Events: PR / push / manual |
| --- | ---: | ---: | ---: | ---: | --- |
| Baseline, 24 hours | 89 | 51 | 27 | 11 | 69 / 18 / 2 |
| Follow-up, 11 hours 54 minutes | 27 | 20 | 2 | 5 | 20 / 7 / 0 |
| Total | 116 | 71 | 29 | 16 | 89 / 25 / 2 |

Elapsed proxy is `updated_at - run_started_at` from each run's latest metadata.
It includes workflow orchestration/finalization, not just runner execution.
Median is the ordinary sample median; p90 is the nearest-rank 90th percentile.

| Window / outcome | n | Median minutes | p90 minutes |
| --- | ---: | ---: | ---: |
| Baseline success | 51 | 13.62 | 15.10 |
| Baseline failure | 27 | 12.52 | 14.02 |
| Baseline cancelled | 11 | 7.08 | 11.18 |
| Follow-up success | 20 | 13.36 | 15.83 |
| Follow-up failure | 2 | 13.03 | 15.72 |
| Follow-up cancelled | 5 | 8.22 | 10.20 |

The sample reproduces the ticket's 89-run counts and approximately 13.6-minute
successful median. The follow-up has fewer latest failures, but different
revisions, unequal windows, repeated correlated PR runs, and documentation-only
work make this an observational comparison, not measured reliability improvement.

Five runs had a second attempt. Their original failures remain failures:

| Run | First attempt | Second attempt |
| --- | --- | --- |
| [42633931480](https://github.com/andrepaim/distillpod/actions/runs/42633931480) | SDK/NDK extraction failure | Test timeout failure |
| [42654537816](https://github.com/andrepaim/distillpod/actions/runs/42654537816) | Emulator connection failure | Cancelled |
| [42667788193](https://github.com/andrepaim/distillpod/actions/runs/42667788193) | Emulator connection failure | Success |
| [42671666425](https://github.com/andrepaim/distillpod/actions/runs/42671666425) | No jobs, Actions startup failure | Restored-queue assertion failure |
| [42671826945](https://github.com/andrepaim/distillpod/actions/runs/42671826945) | No jobs, Actions startup failure | Success |

Across all 121 attempts the outcomes are 71 success, 34 failure and 16 cancelled.
The 20 executed job records in second attempts sum to **118.80 job-minutes**;
that is additional observed job interval, not all wasted time or billed cost.
A successful rerun does not diagnose why the first attempt failed.

## Scheduling, execution and cost boundaries

- All 116 initial `run_started_at` values equal `created_at`, even for runs whose
  jobs started later. Their zero difference is not evidence of zero runner queue.
- Use job `started_at - created_at` for observed queue delay. Among 489
  non-skipped jobs, 480 have nonnegative values: median **2 seconds**, p90
  **3 seconds**. Nine have inconsistent negative timestamps (minimum -920 s),
  excluded from that summary rather than converted to zero. This metric excludes
  dependency waiting before a job is created and does not measure user idle time.
- For execution proxy, use job `completed_at - started_at`; sum parallel jobs
  separately rather than calling their sum workflow wall time. There are 424
  successful jobs (**2143.73 minutes**), 37 failed (**359.63 minutes**) and 28
  cancelled (**196.75 minutes**). Total executed-job interval is **2700.12
  minutes**. The 14 skipped jobs are excluded; their timestamps are not execution.
- Failed plus cancelled jobs account for **556.38 job-minutes** in this sample.
  They are not all avoidable: some failures detected real regressions and some
  cancellations may have stopped obsolete work. Counts include gate jobs and failures in
  runs whose final conclusion was cancelled; do not substitute 37 for 29 runs.
- Isolated Android acceptance accounts for **1368.68 job-minutes** (116 jobs,
  median 12.66 minutes, p90 15.03). Kotlin/lint/APK accounts for **1092.15**
  (119 jobs, median 10.13 minutes, p90 11.85). The latter name includes the later
  lightweight aggregate, so it is not a uniform build-only benchmark. New
  Android-build-validation jobs add 60.35 minutes across six records.
- The timing endpoint for full-main run 42828876719 returns `run_duration_ms`
  764000 but zero billable durations for all six listed Ubuntu jobs. This is not
  a usable execution-cost meter. No billing account data was queried; **actual
  billed minutes and monetary cost are unavailable**. Do not price the interval
  sums or infer paid spend from them.

Execution, failures and cancellations dominate the observed intervals, while
ordinary job queue delay is seconds in the usable records. This does not explain
an agent's entire workday: local builds, diagnosis, review, device validation,
interruption and dependency waiting were outside this audit.

## Failure evidence and causal limits

All 37 failed-job logs were inspected. Buckets describe observed failure stages,
not proven root causes: 29 test/assertion/runner failures, five SDK/emulator
availability failures, and three aggregate-gate rejections. Two additional
failed attempts created no jobs. Multiple symptoms can coexist in one job.

- **Product regression evidence:** queue restore/persistence and transcript
  foreign-key symptoms map to #75 and #81, whose controlled regressions and
  merged validation establish specific repairs. Historical logs do not uniquely
  attribute every failure to those repaired paths. Do not label every assertion
  a product bug.
- **Fixture/test evidence:** Compose thread violations, picker handoff, queue
  cleanup/oracle and notification-dialog symptoms overlap #69, #72, #74 and
  #67. #83 records the reset-control fix; #86 records a local managed-backup
  fixture defect, not a counted hosted failure. Closed tickets retain explicit
  historical-cause limits; passing later runs do not erase those limits.
- **Dependency/tool setup:** attempt 1 of 42633931480 reports an NDK ZIP-content
  read/extraction error. Its second attempt reaches tests and fails differently.
  This supports a setup failure, not a diagnosis of the remote download service.
- **Infrastructure/availability symptom:** four failed jobs lose emulator/ADB
  connectivity. In 42810878145 and 42813065595, all four startup cases pass before
  `adb: device offline`; teardown connection errors are secondary. The process
  death/disconnection cause is unknown. The former run ultimately is cancelled.
  These are plugin-branch observations, not proof of a current-main product bug.
- **No-job startup failures:** attempts 1 of 42671666425 and 42671826945 have zero
  jobs. The contemporaneous [#72 checkpoint](https://github.com/andrepaim/distillpod/issues/72#issuecomment-6747828691)
  records GitHub Internal Server Error and the
  [Actions incident](https://www.githubstatus.com/incidents/tp8cghg7tlc4).
  That historical corroboration is distinct from a fresh status-service query.
- **Gate rejections:** one aggregate rejects failed emulator validation and two
  reject cancelled Android validation. These are correct fail-closed results,
  not three additional independently diagnosed defects.
- **Cancellation:** all 16 sampled logs show cancellation, but no sampled message
  identifies the initiator or proves intentional supersession. Keep intent
  unknown; branch overlap and a newer commit alone are not authorization to
  relabel a cancelled run or rerun it.

### Complete failed-job index

Each row links the exact job for reproduction. Labels are sanitized observations;
unknown historical causes remain unknown. No private runtime identifiers appear.

| Run / attempt | Job | Observation |
| --- | --- | --- |
| 42561270936 / 1 | [128977988574](https://github.com/andrepaim/distillpod/actions/runs/42561270936/job/128977988574) | Automatic-transcription preference unit assertion |
| 42597670694 / 1 | [129094213017](https://github.com/andrepaim/distillpod/actions/runs/42597670694/job/129094213017) | Compose scroll assertion |
| 42600008627 / 1 | [129101842479](https://github.com/andrepaim/distillpod/actions/runs/42600008627/job/129101842479) | Coroutine timeout |
| 42600295369 / 1 | [129102912456](https://github.com/andrepaim/distillpod/actions/runs/42600295369/job/129102912456) | Compose/coroutine timeout |
| 42601187865 / 1 | [129105778666](https://github.com/andrepaim/distillpod/actions/runs/42601187865/job/129105778666) | Coroutine timeout |
| 42601245708 / 1 | [129106117939](https://github.com/andrepaim/distillpod/actions/runs/42601245708/job/129106117939) | Coroutine timeout |
| 42604143242 / 1 | [129115787411](https://github.com/andrepaim/distillpod/actions/runs/42604143242/job/129115787411) | Coroutine timeout |
| 42606815684 / 1 | [129124797102](https://github.com/andrepaim/distillpod/actions/runs/42606815684/job/129124797102) | Persisted playback progress assertion |
| 42607303722 / 1 | [129126428046](https://github.com/andrepaim/distillpod/actions/runs/42607303722/job/129126428046) | Deletion ownership and persisted-progress assertions |
| 42609772509 / 1 | [129134898996](https://github.com/andrepaim/distillpod/actions/runs/42609772509/job/129134898996) | SnapshotStateObserver multithread crash |
| 42617199047 / 1 | [129159900907](https://github.com/andrepaim/distillpod/actions/runs/42617199047/job/129159900907) | Notification permission dialog assertion |
| 42627025307 / 1 | [129193304977](https://github.com/andrepaim/distillpod/actions/runs/42627025307/job/129193304977) | Coroutine timeout |
| 42630045655 / 1 | [129203692570](https://github.com/andrepaim/distillpod/actions/runs/42630045655/job/129203692570) | Startup recovery state assertion |
| 42633931480 / 1 | [129217012652](https://github.com/andrepaim/distillpod/actions/runs/42633931480/job/129217012652) | NDK ZIP extraction failure |
| 42633931480 / 2 | [129225693594](https://github.com/andrepaim/distillpod/actions/runs/42633931480/job/129225693594) | Compose timeout |
| 42634691347 / 1 | [129219783615](https://github.com/andrepaim/distillpod/actions/runs/42634691347/job/129219783615) | Protected-deletion confirmation assertion |
| 42635011062 / 1 | [129220941910](https://github.com/andrepaim/distillpod/actions/runs/42635011062/job/129220941910) | Missing audio request, deletion rejection and timeouts |
| 42639322108 / 1 | [129235862956](https://github.com/andrepaim/distillpod/actions/runs/42639322108/job/129235862956) | Android test-runner instantiation failure |
| 42645796257 / 1 | [129258747955](https://github.com/andrepaim/distillpod/actions/runs/42645796257/job/129258747955) | Restored-queue persistence assertion |
| 42654537816 / 1 | [129289390978](https://github.com/andrepaim/distillpod/actions/runs/42654537816/job/129289390978) | Emulator/ADB connection failure |
| 42657220484 / 1 | [129298895599](https://github.com/andrepaim/distillpod/actions/runs/42657220484/job/129298895599) | Compose thread and queue-order assertions |
| 42657325952 / 1 | [129299205963](https://github.com/andrepaim/distillpod/actions/runs/42657325952/job/129299205963) | Handler created off a Looper thread |
| 42662510694 / 1 | [129317462971](https://github.com/andrepaim/distillpod/actions/runs/42662510694/job/129317462971) | Observer removed off main thread |
| 42667127008 / 1 | [129333676985](https://github.com/andrepaim/distillpod/actions/runs/42667127008/job/129333676985) | UI existence assertion |
| 42667788193 / 1 | [129335965536](https://github.com/andrepaim/distillpod/actions/runs/42667788193/job/129335965536) | Emulator/ADB connection failure |
| 42671666425 / 2 | [129360741897](https://github.com/andrepaim/distillpod/actions/runs/42671666425/job/129360741897) | Restored service queue clear assertion |
| 42680407599 / 1 | [129373081424](https://github.com/andrepaim/distillpod/actions/runs/42680407599/job/129373081424) | Emulator bootstrap Python assertion |
| 42681369012 / 1 | [129376512935](https://github.com/andrepaim/distillpod/actions/runs/42681369012/job/129376512935) | Instrumentation assertion, attribution unresolved |
| 42688318922 / 1 | [129401027339](https://github.com/andrepaim/distillpod/actions/runs/42688318922/job/129401027339) | Document picker did not open |
| 42699639203 / 1 | [129440618053](https://github.com/andrepaim/distillpod/actions/runs/42699639203/job/129440618053) | Transcript foreign-key constraint crash |
| 42706025539 / 1 | [129462916581](https://github.com/andrepaim/distillpod/actions/runs/42706025539/job/129462916581) | SnapshotStateObserver multithread violation |
| 42810878145 / 1 | [129811678403](https://github.com/andrepaim/distillpod/actions/runs/42810878145/job/129811678403) | ADB device offline after four startup passes |
| 42813065595 / 1 | [129819250105](https://github.com/andrepaim/distillpod/actions/runs/42813065595/job/129819250105) | ADB device offline after four startup passes |
| 42819098246 / 1 | [129839759401](https://github.com/andrepaim/distillpod/actions/runs/42819098246/job/129839759401) | Notification Allow-dialog dismissal assertion |
| 42819098246 / 1 | [129847358064](https://github.com/andrepaim/distillpod/actions/runs/42819098246/job/129847358064) | Aggregate rejects emulator failure |
| 42822431542 / 1 | [129855268387](https://github.com/andrepaim/distillpod/actions/runs/42822431542/job/129855268387) | Aggregate rejects cancelled validation |
| 42823622436 / 1 | [129858519056](https://github.com/andrepaim/distillpod/actions/runs/42823622436/job/129858519056) | Aggregate rejects cancelled validation |

## Current-main comparison and actions

The latest full-main run after #67's recurrence repair in
[PR #96](https://github.com/andrepaim/distillpod/pull/96) is
[42828876719](https://github.com/andrepaim/distillpod/actions/runs/42828876719),
passed on `8823737cd2d28d3d7b162a4e1fe58965dc174601`. The next selected Android
run, [42831268540](https://github.com/andrepaim/distillpod/actions/runs/42831268540),
is a documentation-only PR pass. Snapshot main `02f124b` is its report merge;
Android CI was intentionally not started for that Markdown-only push under #88,
and [standalone security passed](https://github.com/andrepaim/distillpod/actions/runs/42831498929).
There is **one full-main post-repair sample**, not enough to establish a failure
rate or that intermittent failures disappeared. Do not compare a docs-only pass
to a full emulator pass as equal work.

Prioritized next actions:

1. **P2: finish already-owned #97 before #89.** Its fresh local diagnostics
   executor timeouts block a truthful Android cache baseline. They are local
   observations, are not counted as CI failures, and do not prove a hosted
   regression. Preserve the original failure and capture bounded executor state;
   do not repeat unchanged until green. Respect that session's ownership.
2. **P2: finish #89's controlled cache measurements**, then #90's build-reuse
   profiling. The elapsed data identifies expensive jobs but does not prove an
   optimization safe or quantify cache savings. Preserve both configurations and
   all acceptance phases, and compare equivalent revisions/workloads.
3. **Investigate emulator loss on recurrence, with the existing PR #93 owner.**
   Retain the two linked offline runs and obtain emulator-process/ADB lifecycle
   evidence before changing fixtures or timeouts. This audit found no uniquely
   established cause or current-main recurrence requiring a speculative fix.
   Do not create a duplicate product bug from the generic offline symptom.
4. **Use #92's separately reviewed bounded-overlap policy** for safe independent
   preparation, with one integration lane and exact-revision ownership. This
   audit does not itself change workflow policy or establish recovered hours.
5. **Reassess with natural post-fix usage**, separating full versus docs-only
   selections and attempt versus run outcomes. There is no new recurring monitor,
   blanket rerun policy or assurance of reliability in this report.

For a verified infrastructure interruption, retain original evidence, confirm
recovery and perform only an authorized bounded rerun; count it separately. For
an assertion, timeout, deterministic fixture or product failure, diagnose and
add the appropriate regression before repair. Do not inflate bounds, delete
assertions, suppress failed checks or silently replace an artifact to get green.
An outage or device-unavailable gate is a blocker, not permission to merge.

## Validation and limitations

Report-only risk: inaccurate attribution or timing could prompt unsafe workflow
changes. Review arithmetic, evidence links, category boundaries and local links;
run `git diff --check` and the repository's documentation validation. No Android,
emulator or physical-phone run is needed for this text-only change. PR checks,
current-main integration and the selected combined-commit checks remain required.
No production state, credentials, device data, release metadata, branch protection,
paid service or unrelated work was changed. No test pass is claimed for an
unexecuted stage, and this report does not close any independently owned issue.
