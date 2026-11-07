# Reliability analytics

The optional Sentry integration now records durable, content-free operation revisions in addition to diagnostic logs. Settings → Diagnostics contains independent **Share diagnostics** and **Share usage statistics** controls. Both default off on fresh installs; an existing diagnostic preference is preserved. Usage remains off until separately chosen. No new account, paid backend or Firebase deployment is required.

## Contract and interpretation

`operation.summary` schema 1 contains random summary/operation/attempt identifiers, feature, outcome, safe reason/source, monotonically increasing operation sequence, observation/start timestamps, elapsed time and originating operation release. Envelope release identifies the uploading app; `operation_release` identifies the revision's app version. Elapsed time is monotonic within an attempt in the same process; `-1` means unavailable after restart. It includes waiting within that attempt and must not be labelled CPU/inference time. Transcription processing passes have separate attempt identifiers. Repeated completion callbacks are ignored. A later explicit generation attempt can follow a completed generation.

The private atomic journal under `noBackupFilesDir/reliability` keeps at most 500 revisions for 72 hours. It is separate from library data, exports and synchronization. Transitions are persisted synchronously before best-effort detailed log dispatch; failures are suppressed to keep app actions working. Startup reconciles only previously observed unfinished jobs against current transcript and saved-insight state. An unmatched playback/voice operation becomes unknown, never assumed successful. It does not upload the existing library when consent is enabled.

A stable summary ID survives replay into the envelope queue. A queued marker means the envelope was durably written, not remotely ingested. Process death between the two writes can duplicate a summary. Envelope eviction or provider rejection can still lose it. Retention, queue limits, consent withdrawal, private-storage errors and a device that never restarts can leave unobservable gaps. This is observational telemetry, not an audit system.

Observed states are queued, running, waiting, paused, completed, failed, cancelled, interrupted and unknown. Transcription stage transitions retain categorical stage reasons. Captures count as completed once saved. Distillation completion requires a saved, reviewed result. Voice tracks microphone start, first text, first output submission and successful return from the restoration command separately. `resume_requested` does not prove audible playback; `voice.audio` means output accepted PCM, not that the owner heard it. Playback operations currently describe service lifetimes, not individual episode listening sessions. Startup/buffering timing and observed playing intervals are diagnostic signals; an abruptly terminated service cannot contribute its final listening duration.

`transcription.quality` reports source/model/language categories, audio duration, coverage duration and empty output. These indicate pipeline health, not word accuracy. Models/languages outside the allowlist become `other`; no text is uploaded. No recordings, transcripts, titles, URLs, search queries, prompts, answers, account identity or provider tokens enter these fields. Native Whisper crash collection/symbolication remains a separate increment.

`usage.feature` requires independent usage consent and counts approved user actions (capture, transcription preparation, distillation, voice, playback start/resume). Counts describe actions, not unique users or retention. Reporting installations have random device-local identifiers. Diagnostics can be disabled while usage remains enabled and vice versa. Revoking either stream purges the shared pending envelope queue to avoid retaining revoked data; the other stream remains enabled. In-flight uploads may finish, and provider deletion/retention governs transmitted data.

## Dashboards

Five versioned definitions live in `scripts/telemetry/*.json`. They default to production, the selected project, seven days, schema 1 and exclusion of synthetic test traffic:

- [Release health](https://paim-3q.sentry.io/dashboard/10707923/)
- [Playback and controls](https://paim-3q.sentry.io/dashboard/10707922/)
- [Transcripts and distillation](https://paim-3q.sentry.io/dashboard/10707924/)
- [Voice conversations](https://paim-3q.sentry.io/dashboard/10707926/)
- [Usage and reporting health](https://paim-3q.sentry.io/dashboard/10707925/)

Outcome widgets count distinct **summary revisions**, grouped by outcome. They show observed transitions, not the number of jobs currently in a state. A job may appear in multiple outcome groups after retries. Do not divide those widget counts to calculate success rates. Detailed-log counts use stable event IDs; usage events count actions.

For aggregate job rates, export the operation-summary attributes over the selected reporting window and run:

```sh
python3 scripts/telemetry/reliability_report.py /private/path/summary-attributes.json
```

The report deduplicates summary IDs, selects the highest observed sequence per installation/feature/operation, and reports observed jobs, attempts, latest outcomes and recovered jobs. Resolved success rate is completed / (completed + failed). Cancelled, interrupted, unknown and outstanding work remain visible and are excluded from that denominator. No resolved jobs means no rate. Rates cover only consenting reporting installations and the query window; starts or retries outside that window are not reconstructed. Production and synthetic exports must remain separate. Sample counts accompany every interpretation; small samples do not justify regression or percentile claims.

Definitions can be reviewed and deployed idempotently with `deploy_dashboards.py --token-file /private/token --organization ORG --project-id ID`, first without `--apply` and then with it. Only matching DistillPod titles are updated. The operator token is not an application credential and must never enter Git or CI artifacts. Sentry's MCP connection currently reads dashboards but cannot create them; deployment uses the official dashboard API.

## Reporting health and limits

Settings shows pending envelopes, locally observed dropped reports and the last HTTP acknowledgement. Hourly `diagnostics.health` reports expose queue depth and drop counters while diagnostics is enabled. These are last-observed values, not a remote heartbeat: Android/offline/force-stop delays apply. Existing 5 MB / 1,000-envelope / 72-hour limits, per-device daily budgets and retry backoff remain. No per-second/per-token collection is added. A journal summary has upload priority over detailed logs during periodic recovery. Cloud quota exhaustion can still prevent reporting the gap itself.

Keep the organization on free Developer with billing disabled. Current Logs documentation specifies a seven-day Developer query window; verify the account before promising monthly trends. Existing project issue alerts remain; notification destinations are unchanged. Dashboard definitions and a verified export report provide the initial reliability view; minimum-volume regression alerts and long-term rollups require subsequent baseline data and review.

## Validation

Automated coverage includes retry identity, duplicate callbacks, stable replay, recovery/unknown outcomes, missing timing, backward wall-clock changes, journal bounds, independent consent, revocation, content rejection and report denominators. Existing delivery, privacy, migration, backup, sync and voice regressions remain required. Physical-device evidence is recorded separately in the delivery receipt; synthetic fixture outcomes are explicitly excluded from production aggregates.
