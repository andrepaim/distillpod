# Private cloud-only distillation investigation

Issue #38 extends the existing Explore exporter. It does not change Sentry configuration,
deploy a backend, contact a provider, regenerate AI output, inspect a phone, or notify anyone.

## Read-only workflow

Use an **already authorized** operator read token in a private file. These examples use
placeholders, not a credential location discovered on the owner's computer. Never paste
tokens into the command line, issue tracker, report, or CI output. Do not create credentials
or change account permissions to run this workflow.

```sh
python3 scripts/telemetry/export_operations.py --investigation \
  --token-file /private/existing-read-token --organization ORG --project-id ID \
  --environment production --start 2026-01-01T00:00:00Z --end 2026-01-02T00:00:00Z \
  --output /private/incident-export.json
python3 scripts/telemetry/investigation_report.py /private/incident-export.json \
  --reference RANDOM-UUID-FROM-OPERATION --output /private/incident-report.json
```

Run on a POSIX filesystem (including WSL). Outputs are atomically replaced with mode 0600,
symlink destinations are refused, and newly created destination directories request mode
0700. Choose a private directory outside repositories and synced/public folders. Existing
parent directory permissions are not changed. The programs print only completion/status,
never report contents or credentials. They do not upload or publish output. Even
content-free reports contain installation/activity references and must remain private.

The exporter validates exact producer event/reason/model/outcome allowlists, canonical random
IDs, typed numeric/boolean values and bounded metadata. Unexpected values become unavailable,
not pass-through strings. The exporter queries only selected content-free attributes for capture, transcription,
distillation, operation summaries, and reporting health in the existing US Sentry endpoint.
It rejects redirects before transmitting credentials to a different endpoint. Pagination
may change only the cursor, not organization/project/environment/query/window. The window
must have explicit timezone offsets, be positive, and be at most seven days. There is a
10,000-unique-row/100-total-request cap across all column batches. A full page without pagination metadata, malformed response,
partial-result signal, repeated/unsafe cursor, changed scope, cap, or API failure yields
an explicitly incomplete export and exit status 2. Observations already retrieved remain
available for diagnosis; they never become a clean success/failure rate. Narrow the window
after a cap. Do not treat an exit-2 artifact as an exhaustive incident record.

The [official Explore table API](https://docs.sentry.io/api/explore/query-explore-events-in-table-format/)
permits at most 20 selected fields. The exporter uses the documented singular
`tag[name,type]` form for custom columns and includes its sort column. It requests column
batches with identical project/environment/window/filter and repeated event/installation/
summary/schema/observation/emission anchors. It joins by canonical event ID plus installation
ID, never by row position. Different key sets, conflicting anchors, missing identity or a
failed batch make the entire export incomplete; only previously verified columns remain.
This is not an API snapshot: stable event contents are assumed immutable, and events can
arrive after the last request. More columns consume more of the shared 100-request budget,
so practical safe windows may be much smaller than 10,000 rows.

The API window is a **provider ingestion window**, not original observation time. A late
summary can refer to an earlier incident. Choose a bounded window that includes possible
delivery delay, and repeat a read later when appropriate. Provider retention or limits can
make evidence unavailable even after successful pagination. No real Sentry query or
production payload was used to validate this change; API behavior is covered with synthetic
response fixtures, and unsupported server fields/errors are reported as incomplete reads.

## How to read the result

- `capture_acceptance`: durable capture.completed/capture.saved or completed capture-operation evidence. A start or input-intent marker alone does not prove a saved capture.
- `timeline`: observed event/summary ID, original `observed_at_ms`, separate
  `emitted_at_ms`, provider timestamp, and emission delay when comparable. Missing original
  time sorts last and is flagged; ingestion time never substitutes for observation time.
- `fields` and `unavailable`: recorded stage reasons, review flags, bounded issue counts,
  model/prompt categories, request timing/call counts, context-readiness measurements, retry
  trigger and change flags. Missing, unknown, unsupported versions and unmeasured values
  stay unavailable. A missing retry comparison is not false. Segment zeroes accompanied by
  `segments_measured=false` are unknown, not empty context.
- `attempts`: explicit generation-attempt IDs and their separate execution IDs.
  Review-only resume retains the attempt but has another execution, cached generation,
  and zero generation calls. Retry comparisons are read from the producer, never inferred
  from timestamps, legacy `attempt_id`, matching content, or nearby events.
- `publication`: evidence of publication **somewhere in this observed history**, not the
  current saved state or the status of a later retry. Abstention/rejection/failure remain
  visible in their own stage records. No publication record means unknown, not proof that
  AI never ran. A start marker alone also does not prove a provider invocation.
- `reporting_health` and `gaps`: last observed admission budget/counter evidence, missing
  capture/context/stage evidence, unsupported versions, conflicts, and export limitations.
  Policy 2 has routine 800, critical 160, health 10, errors 30, total 1000. Used/limit values
  show observed exhaustion; dropped counters include admission suppression of pending
  summaries and are not unique lost operations or proof of permanent loss.
- `inferences` explicitly labels the causal grouping. Every source field is observed;
  missing facts remain unavailable. No aggregate success/failure rate is produced.

Correlation uses version-1 typed random IDs within one installation. Capture/moment/
generation/execution identities and explicit previous-attempt links connect retry history.
Transcript revisions can be shared by multiple captures, so they are **not** a generic
cross-capture join. Explicit `capture.transcript_link`/`transcription.dependency`
`transcript_revision_id` links (with legacy `transcript_id` fallback on those explicit
dependency events only) may retrieve transcription lifecycle records whose operation ID is
that revision. A reference found in multiple installations is rejected as ambiguous.
Legacy records without the explicit contract cannot recreate a missing causal chain.
The capture.accepted input-intent event can lack identity, so absence from this causal
report is not proof that no acceptance occurred. Linked capture.started/completed/saved
records retain their own lifecycle meaning.

Stable summary IDs deduplicate replayed summaries; event IDs deduplicate raw delivery.
Conflicting records for the same stable ID are excluded and flagged rather than choosing
a convenient outcome. Original observation time orders the timeline; operation sequence
is retained for interpreting same-operation revisions and wall-clock changes.

Content-free telemetry explains the **recorded pipeline decision**. It cannot independently
prove semantic correctness of a model judgment, recover transcripts, reconstruct historical
fields never collected, prove completeness, or establish provider billing. Attempted calls
are gateway invocations in this execution, not tokens or billing/network-retry counts.
Coverage is source interval coverage, not semantic quality; context counts describe persisted
context only when `frozen_context=true`.

## Producer contracts and validation

Required producers are #34 budget policy 2, #35 `distillation.attempt_summary` outcome 1,
#36 `capture.context_summary` context schema 1, and #37 correlation 1. Their app
implementation, integration, and repository gates remain prerequisites. This consumer does
not deploy those app changes, enable consent, backfill old data, or waive their device gates.

Tests use only synthetic identifiers, response fixtures and selected typed attributes from
actual producer-test envelopes. See [fixture provenance](../../scripts/fixtures/investigation/README.md).
The consumer covers publication, generator abstention, reviewer rejection, missing context,
failed/retried execution, review-only resume, duplicates/conflicts/replays, late summary
ordering, unknown versions and capped/failed/gapped reads.

```sh
python3 -m unittest discover -s scripts -p 'test_investigation_report.py'
python3 -m unittest discover -s scripts -p 'test_*.py'
git diff --check
```

No owner-phone or live AI/Sentry inspection belongs to this task. Full app gates are separate
from the Python checks; a passing fixture suite is not evidence of deployed instrumentation
or a complete real incident.


## Reconciliation and risk assessment (2026-11-09)

Current-main policy baseline: `04754ade` (PR #50 risk-based validation, with PR #44).
The report branch integrates that baseline without merging speculative producer branches.

Affected behavior is a POSIX/WSL Python command-line reader and private file writer:
Sentry query construction/pagination, selected-field normalization, UUID evidence joins,
and local JSON output. It changes no Android code, lifecycle, background scheduling,
permissions, notifications, Bluetooth/audio routing, persisted app schema, backup/sync
format, signing identity, upgrade path, or owner-device state. Real risks are misleading
incomplete evidence, cross-incident joins, unsafe credential forwarding, content leakage,
and output permissions. Synthetic API/error/drift fixtures, real synthetic producer
envelopes, privacy sentinels, actual CLI/file-mode checks, code review and repository
security checks directly exercise those risks.

For this consumer, physical-phone and local Android emulator testing are not applicable:
they cannot establish a Python/API batch-join or POSIX permission property, and no app flow
is modified. This is a per-change assessment, not a waiver for reporting producers.
Existing PR Android/emulator/security CI remains unchanged and must pass on the final
integrated head. No live Sentry read was used as validation; actual authorization/account
availability is therefore unverified. No credentials or account configuration were changed.

Committed producer schemas were compared read-only against the captured fixture contracts:
- #34 `a476031`: budget policy 2, same limits/counter semantics as fixture-era `a0d79a7`.
- #37 `d4663fc`: correlation 1 fields/retry allowlists unchanged from `812c4d5`.
- #35 `350133c`: outcome 1 fields/models unchanged from fixture producer `6676b03`.
- #36 `e795e1e`: context schema 1 fields/reasons unchanged from `7d53f9c`.

The corresponding producer source files have no diff across those pairs.
These comparisons establish compatibility with those committed schemas, not their presence
on main or successful end-to-end deployment. Keep #38 open until #34/#37/#35/#36 land,
then compare the actual combined producer definitions, integrate current main again, and
rerun consumer/security checks and required CI. Any changed event, enum, field type,
identity or serialization contract requires renewed fixture validation.


## Combined producer candidate validation

Local-only reconciliation uses the exact published #36 candidate
`a987d69e84b5372dd39428283c6411263241fecf` (tree
`41e005b8794b2a147e7ec4622b2d3f1afd008c15`), which includes actual main
`b9d4d162` and producer #34/#37/#35 code. At preparation time #36 is not yet
merged; this candidate is not represented as completed main integration.

The candidate's DiagnosticCorrelation, DistillationOutcome and
ContextPreparationEvidence files are byte-identical to the previously verified contract
versions listed above. DiagnosticBudget adds capture.context_summary to the critical
admission lane; policy version 2, limits and emitted health fields are unchanged.
Therefore the actual serialized synthetic fixtures remain valid without fabricating or
rewriting producer values.

The new synthetic end-to-end pass projects those real producer attributes through the
documented Explore column shape, reads all bounded column batches, and creates reports.
It verifies the actual quota-to-review-resume chain, separate execution IDs, cached
generation with zero new calls, publication evidence, missing-context unknown publication,
and incomplete observation after a later batch failure. Export and report files are
written and checked as mode 0600. Only the simulated provider timestamp and response
ordering are supplied by the API fixture; application fields come from producer output.
No real API response or owner content is used, and passing this test establishes neither
production credentials nor cloud-field availability.

The consumer's own risk assessment is unchanged: it modifies read-only CLI/API processing
and private POSIX files, not Android behavior. Producer-specific app validation remains
owned by the prerequisite changes. Before publication, wait for #36 to merge, verify tree
equivalence with actual main or review/retest any differences, then run required CI on the
final published consumer head. This local preparation does not authorize premature merge,
retargeting, live inspection or release.


## Context-wait observation limits

Context-summary records are observed snapshots, **not one record per preparation
callback**, a preparation denominator, or a count of lost attempts. Corrected #36
coalesces only unchanged transcript_required waits after successful local outbox insertion,
for the same actual moment/attempt/execution and meaningful input snapshot. The first or
changed wait and ready/terminal results remain eligible to attempt emission; this is not
a delivery guarantee. The bounded 160-entry cache resets with consent/day/process lifetime.
No additional wire fields or higher budgets identify suppressed callbacks.

Consequently the consumer cannot tell from a missing row alone whether a callback was
coalesced, never occurred, was budget-denied, or was otherwise unobserved. It must not
invent coalescing counts or use context-row counts as a denominator. Stable event-ID replay
deduplication is separate: two distinct observed events with equal metrics remain two
observations, including after cache resets.

Direct context and detailed attempt summaries are best-effort and may be dropped at the
critical cap. Generic terminal operation summaries have separate journal replay within
existing retention/queue limits; their later observation does not restore missing context
or review details. Aggregate critical_dropped combines admission-denial/deferred evidence
and is not a lost-attempt count. A journal completion can support observed publication
while detailed stage evidence remains unavailable.

This consumer clarification does not merge or validate the unpublished producer correction.
Original observation times absent from historical producer fixtures remain unavailable,
never reconstructed from emission time.
