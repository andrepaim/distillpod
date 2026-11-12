# Reproducible CI throughput measurement

Tracking: [#131](https://github.com/andrepaim/distillpod/issues/131).
This measures Android CI (`ci.yml`), not release/signing or standalone security
workflows. No application content, job logs, PR titles or branch names are
included in the aggregate output. Raw API snapshots remain private local files.

## Collect and analyze

Use an authenticated `gh` CLI and Python 3; collection uses only GET requests.
Choose explicit UTC start/end timestamps, with an inclusive start and exclusive
end. The collector returns exit 2 for incomplete reads and retains coverage
errors in its private snapshot. Analyze incomplete snapshots as lower bounds,
not complete rates. Output snapshots must be new paths and are created mode 0600.

```sh
python3 scripts/ci_metrics.py collect \
  --repo andrepaim/distillpod \
  --start 2026-11-12T11:39:00Z --end 2026-11-12T13:05:00Z \
  --max-pages 3 --max-runs 30 --max-logs 10 --max-requests 200 \
  --output /tmp/distillpod-throughput-post.json
python3 scripts/ci_metrics.py analyze /tmp/distillpod-throughput-post.json
```

Each list endpoint is paginated and checked for totals, duplicate pages and
bounds. There is an overall request budget as well as page/run/log limits.
GitHub limits filtered workflow search to 1,000 results: narrow or partition
larger UTC windows and retain coverage errors. Pull records provide a fallback
when a run lacks PR associations; repeated/reused branch names are ambiguous and
remain unassociated. API reads are not an atomic snapshot; reruns or repository
changes during collection can require a fresh bounded window.

The earlier audit exports can be imported read-only, without recollecting all
historical jobs:

```sh
python3 scripts/ci_metrics.py import-audit \
  --repo andrepaim/distillpod \
  --start 2026-10-15T00:00:00Z --end 2026-11-12T00:00:00Z \
  --runs /tmp/distillpod-audit-runs.json \
  --jobs-dir /tmp/distillpod-ci-audit \
  --pulls /tmp/distillpod-audit-prs.json \
  --output /tmp/distillpod-throughput-baseline.json
python3 scripts/ci_metrics.py analyze /tmp/distillpod-throughput-baseline.json
```

These import paths name the private original audit, not repository fixtures.
Legacy all-attempt jobs lack old attempts' metadata. Their execution intervals
remain available, but the latest run start cannot describe a historical attempt.
Missing wall/queue evidence is counted explicitly; it is not filled with zero.

## Accounting definitions

- Runner minutes sum distinct non-skipped job execution intervals. Parallel jobs
  add runner time while overlapping in elapsed time. Billed minutes are unknown;
  neither per-job rounding nor runner totals establish a bill.
- Failed-only reruns can copy a successful job under a **new job ID**, retaining
  its original runner and start/end timestamps. Deduplicate within a run by
  job name, runner and execution interval. The copied result still participates
  in logical validation status; it is not another physical execution. Rerun
  minutes include only newly executed intervals.
- Attempt wall time runs from that attempt's `run_started_at` to its last job
  completion. It excludes workflow finalization. Execution span runs from the
  first newly executed job start to the last newly executed job completion and
  excludes initial orchestration. Samples and missing values are reported.
- Job queue delay is `started_at - created_at`; dependency waiting before job
  creation is excluded. Initial dispatch delay is first-attempt
  `run_started_at - run.created_at`, not runner queue delay. Inconsistent negative
  queue timestamps are counted as unavailable, not silently clamped.
- A draft preview requires the exact incomplete-draft gate marker, all four
  expensive jobs skipped, and no other failed job. Without this evidence a
  workflow failure is not relabelled as an intentional preview. The log-read
  budget/error is visible. Failure classifications do not establish root causes.
- Cancelled-attempt minutes include all newly executed jobs in that attempt.
  A later same-event/repository/branch run created before cancellation's last
  completion is only a **candidate supersession**. Overlap does not establish
  that concurrency or an intentional push caused cancellation.
- Runs per PR count workflow starts; full-successful attempts count logical
  successful validations, including failed-only reruns that reuse earlier job
  results. Per-attempt rows preserve runner/wall measures separately. Branch
  fallback requires one matching PR. Median is ordinary; p90 is nearest rank.

## Initial comparison and correction

The following is a bounded snapshot collected November 12, 2026, before the next
optimization increment. Baseline run/job exports were complete for their query;
post-change collection read all seven runs, all seven attempts and all 49 job
records, and checked three failed-gate logs for the draft marker. Both samples
have complete execution accounting. Baseline wall time is available for only
335 of 348 attempts; two historical attempts have no jobs/outcome metadata and
remain unclassified. Baseline PR matching covers all 228 PR workflow starts.

| Metric | Baseline: Oct 15–Nov 12 00:00 UTC | Post-change: Nov 12 11:39–13:05 UTC |
| --- | ---: | ---: |
| Workflow starts / attempts | 335 / 348 | 7 / 7 |
| Distinct observed execution minutes | 6,890.82 | 86.17 |
| Carried-forward result records excluded | 34 | 0 |
| Logical full-successful attempts | 211 | 4 |
| Full-success wall median | 764 s (n=211) | 733.5 s (n=4) |
| Verified incomplete drafts | 0 | 3 |
| Draft wall median | unavailable | 20 s (n=3) |
| Observed non-draft failure attempts | 79 | 0 |
| Cancelled attempts / execution minutes | 50 / 636.42 | 0 / 0 |
| Candidate superseded attempts / minutes | 44 / 537.23 | 0 / 0 |
| PR workflow starts / distinct PRs | 228 / 82 | 5 / 2 |
| Median workflow starts per PR | 2 | 2.5 |

The original audit's approximately 7,013 **Android CI** minutes double-counted
34 carried-forward results. Its corrected Android figure is 6,890.82 minutes.
This changes accounting, not the retained failure evidence. The previously
quoted 7,622 minutes covered **all workflows**, so it must not be compared with
this Android-only figure. Applying the same interval deduplication to those 622
all-workflow exports gives 7,481.58 observed minutes, excluding 41 carried-forward
records and two inconsistent negative intervals; those two intervals are
unmeasurable, not evidence of zero consumption.

Post-change samples include [PR #127](https://github.com/andrepaim/distillpod/pull/127)
and [PR #128](https://github.com/andrepaim/distillpod/pull/128):
[draft probe](https://github.com/andrepaim/distillpod/actions/runs/43211052122),
[ready validation](https://github.com/andrepaim/distillpod/actions/runs/43211190710),
[merged main](https://github.com/andrepaim/distillpod/actions/runs/43213442174), and
[later main](https://github.com/andrepaim/distillpod/actions/runs/43217143283).
The three draft previews used 1.05 runner-minutes combined and did not run the
four expensive jobs. This confirms routing behavior, not hypothetical minutes
saved had the changes been submitted differently.

The windows, changes, runner/cache states and sample sizes differ. The post
sample has only two PRs and four full successes; zero observed failures does not
establish reliability, and its medians do not prove a causal overall speedup.
Collect later comparable windows and report full starts per PR, cancellation and
rerun minutes, failure evidence, and phase/whole-run times together. Root records
final optimization-run evidence in the issue/PR rather than creating an extra
measurement-only CI cycle. No recurring schedule is configured by this tool.
