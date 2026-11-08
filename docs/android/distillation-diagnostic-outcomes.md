# Distillation attempt outcomes

The additive `distillation.attempt_summary` event uses `outcome_version=1` alongside the existing envelope `schema_version=1` and correlation contract. One summary describes one acquired execution, not the entire lifetime of a saved moment. The stable summary_id is the persisted random execution_id; retries of the same serialized envelope retain this ID and observed_at_ms. emitted_at_ms is separate. Existing operation summaries retain their lifecycle meaning. These fields do not reconstruct historical observations.

## Contract

- `stage`: generation, review, or publication. `stage_outcome` is a finite category, never a provider message.
- `generation_outcome`, `review_outcome`: not_run, unknown, cached, ready/accepted, generator_needs_context, generator_unclear_source, review_rejected, invalid_response, interrupted, cancelled, auth_failure, quota, request_failed, stale_result_suppressed, or internal_failure as applicable.
- `passage_complete`, `takeaway_supported`, `relevant`, `quote_faithful`, `needs_context`: booleans when validated review evidence exists, otherwise unknown.
- `issue_incomplete_count`, `issue_unsupported_count`, `issue_irrelevant_count`, `issue_quote_count`: counts of validated allowlisted issues (0–20), or -1 when no valid review exists.
- `contradictory_review`: true when a positive review flag conflicts with a corresponding issue, or needsContext is true despite otherwise positive takeaway checks; unknown without validated review. This observation never changes acceptance policy.
- `generator_model`, `reviewer_model`: finite allowlisted public identifiers, unknown for unrecognized/unobserved identifiers, not_run when the stage has neither run nor cached evidence. Never accept arbitrary provider text as a model identifier.
- `prompt_version`: the existing prompt contract version, without prompt text.
- `generation_elapsed_ms`, `review_elapsed_ms`: monotonic elapsed milliseconds for gateway requests in this execution, including failed or malformed responses, bounded to 24 hours; -1 when the stage did not execute. Cached stages do not pretend to have fresh timing.
- `generation_attempted_calls`, `review_attempted_calls`: gateway invocations initiated in this execution, including failures. These are **not provider billing, token usage, or underlying network retry counts**. A resumed review can have zero generation calls.
- `quote_omitted`: true/false after successful publication, otherwise unknown. A faithful takeaway published after quote-only rejection uses published_without_quote.

Terminal stage/outcome fields distinguish abstention from review rejection, malformed responses from request failures, and obsolete work from publication. No transcript, prompt, candidate, quote, issue explanation, raw response, credential, or URL is serialized. Existing consent and failure-isolation boundaries apply. Isolated instrumentation cannot emit these summaries. Explicit user cancellation remains its own correlated lifecycle event; a returning cancelled execution has stale_result_suppressed rather than a fabricated provider failure. Process death cannot execute a finalizer: the existing operation journal reports interrupted recovery, not fabricated stage evidence.

## Validation gate

Synthetic automated tests cover these categories and envelope privacy. Emulator acceptance and fresh non-destructive physical-phone validation are still required before merge; no live AI calls are needed for automated coverage.
