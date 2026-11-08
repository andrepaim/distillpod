# Synthetic investigation fixture provenance

These files contain selected content-free attributes from **actual serialized Sentry log
items** emitted by synthetic JVM producer tests, not hand-authored envelope field guesses.
Random IDs, timestamps and model categories here describe fixtures only. No owner's
activity, device payload, credential, transcript, title, URL, prompt or answer was collected.

Context fixtures were emitted by
`CaptureContextEvidenceTest` and `ContextPreparationEvidence.fields()` in issue #36,
source worktree revision `ebb589fbdca4b1dc1fb4c8ca144e457412861712` plus its tested producer
changes. Cases: ready, missing coverage, partial coverage, unknown duration, empty speech,
oversized context, source mismatch, revision mismatch. The producer records typed values
under `items[].attributes.*.value`.

Projection preserves each item's event `body` and only the report's selected typed
`attributes`. SDK envelope metadata, simulated user/device attributes, trace/span IDs and
unselected attributes are removed. No selected values are fabricated or rewritten.
Consumer tests normalize these producer-shaped objects directly, then reconstruct reports.

Regenerate with the owning producer's synthetic test fixture export. Never substitute real
Sentry/phone output, and never refresh snapshots by querying owner telemetry. When producer
contracts change, review the source and actual emitted values before updating expectations.

Stage fixtures were emitted by #35 DistillationSummaryTest through the actual
Diagnostics.distillationSummary serializer. Validated cases: generator_needs_context,
review_rejected, quota, published_without_quote and published after review-only resume
(cached generation and zero new generation calls). Only producer cases reported passing
were selected; a separate unfinished storage test output was excluded.

Stage snapshots were refreshed from the fully green v3 export at producer commit
6676b03, including #37 consent-epoch dependency 812c4d5; all 15 producer tests passed.
The final snapshots include stale-result suppression and storage-failure classification.

Lifecycle snapshot comes from #37 production commit812c4d5, deterministic
CaptureRepository paused-after-claim/cancel/resume test. A temporary test-only export
hook was removed after the passing run. This actual stream contains durable capture
started/completed/saved/link/prepared, distillation execution lifecycle and operation
summaries. It deliberately contains no capture.accepted (separate input-intent boundary).
