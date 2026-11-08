# Capture and generation correlation (version 1)

This contract is independent of the event transport schema. Both raw logs and
durable operation summaries carry `correlation_version=1`. It describes identity,
not content or product state. No provider request, prompt, transcript, output,
account identifier, URL, model name, or content hash is included.

## Identity and dependency fields

- `capture_request_id`: independent random UUID for each save request. Duplicate
  taps have different requests but point to the same `moment_id`.
- `moment_id`: the durable saved moment UUID.
- `generation_attempt_id`: the saved candidate/source attempt UUID. Explicit
  regeneration creates a new attempt and links `previous_attempt_id`.
- `execution_id`: a new UUID for each execution, including review-only resume.
  Another execution of the same candidate is not another generation attempt.
- `transcript_revision_id`: the random transcript revision used or awaited.
  `capture.transcript_link` and `transcription.dependency` explicitly connect a
  moment/attempt to a revision created or replaced after initial capture.

New diagnostic provenance is collected only while configured diagnostic reporting
is enabled. A private consent epoch invalidates previously collected local
provenance on revoke/re-enable, without Room calls from diagnostic locks. Old
metadata may remain in untouched local attempt rows but is ignored and removed
when touched. Epochs are excluded from telemetry, journal serialization, AI input,
and backups. In-flight evidence from a revoked epoch is not emitted under new
consent. Functional attempt/run IDs remain ordinary application state.

The original capture request and previous attempt are retained in local attempt
provenance through preparation and restarts. This local metadata is stripped from
AI prompt input and is not part of portable source records. Legacy records are
not backfilled with invented requests or ancestry.

## Retry evidence

`retry_trigger` is limited to initial, explicit_retry, expanded_context,
transcript_changed, review_resume, process_recovery, or unknown. Boolean fields
`context_expanded`, `transcript_changed`, `model_changed`, `prompt_changed`,
and `candidate_reused` report only comparisons supported by locally retained
evidence. An absent flag means unknown, not false. Model names and prompt bodies
are never exported. Candidate reuse is captured at execution start, not inferred
from the existence of a candidate at completion.

Changing context or transcript for a new attempt is distinct from an execution
that resumes review of a saved candidate. No new automatic AI retry is introduced.

## Timing, replay and incomplete evidence

`observed_at_ms` is captured synchronously at the call site, before queueing raw
emission. Summaries persist their original observation time and operation start
as `operation_started_ms`. `emitted_at_ms` is local emission time; server
ingestion time is separate and must not be used as observation time.

`summary_id` is stable for a stored transition across recovery and replay.
Deduplicate summaries by this ID and order within an installation by `sequence`.
The sequence survives pruning and reopening. Raw `event_id` is the identity of
that raw emission, not a generation attempt. The legacy `attempt_id` remains for
compatibility but is not an alias for `generation_attempt_id`.

`correlation_complete` means all five primary identity links are present. It
does not imply that every optional comparison is known. Transcription-only
records and captures before a transcript exists are intentionally incomplete.
Version-1 journal records remain readable with missing correlation. Unknown
outcomes and missing evidence must not be attributed to product failure.

Generation starts are observed synchronously at the serialized claim boundary,
before the calling coroutine can suspend behind a newer cancellation/resume.
Raw emission remains asynchronous and provider calls remain outside the Room
transaction. A rare transaction commit failure can leave an attempted start in
the journal: it is not proof that a provider request ran and recovery may mark
it interrupted or unknown.

A terminal callback carrying an older execution cannot overwrite the latest
journal state. An identityless callback cannot mutate a correlated lifecycle.
Raw events retain the identity the producer supplied, never a guessed latest
execution. Rejected stale generation writes use `distillation.discarded` with
reason `superseded`; they are not successful or failed current executions.

## Validation

Focused JVM tests cover persistence/replay, dependency changes, generation versus
execution identity, cancellation, stale and identityless callbacks, and recovery.
Repository tests cover durable request provenance across duplicate taps,
preparation and explicit context expansion, and exclusion from AI input.
Full emulator acceptance and fresh non-destructive phone validation remain
required before merge; unit tests do not replace those gates.
