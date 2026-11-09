# Context readiness diagnostics (schema 1)

Issue #36 adds one bounded `capture.context_summary` for each entered preparation, including waits, stale writes, cancellation and exceptions. It uses the #34 critical admission lane (shared 160/day reserve within the 1,000/day total), normal reporting consent and existing queue/redaction. Admission is not guaranteed delivery. No event is emitted when reporting is disabled/unconfigured. The preparation snapshots its collection epoch at entry; disable/re-enable during preparation suppresses the old summary even if its frozen identity was refreshed later. Epoch guards are local-only and never serialized.

## Semantics

- `context_schema_version=1`; identity uses #37 UUID-only correlation. No content-derived identifier, transcript text, audio, URL/title, prompt, candidate or exception message is accepted.
- `transcript_source`: publisher, whisper, online, missing, other. Online remains excluded from context exactly as before.
- `outcome`: queued, waiting_auth, transcript_required, no_speech, needs_context, review_source, superseded, episode_missing, cancelled, failed.
- `context_reason`: ready, coverage_missing, source_mismatch, revision_mismatch, empty_speech, empty_context, oversized, invalid_bounds, episode_missing, superseded, cancelled, internal.
- `expanded_context`, `backward_window_ms` (120000 or 180000), and `forward_window_ms` (15000) describe configured windows, before duration clamping. `duration_known` says whether episode or matching complete transcript supplies an end boundary.
- `coverage_measured` gates `missing_duration_ms`, the requested interval length minus the union of available coverage intersections. `coverage_complete` is the existing source-matching coverage predicate. A mismatching source may have zero missing duration but still be incomplete for this preparation. Coverage is not transcript accuracy or semantic quality.
- `segments_measured` gates input, usable and boundary counts. Unknown measurements have zero counts and false measurement flags.
- `input_segment_count` is the overlapping DAO window input. `excluded_left_count` and `excluded_right_count` independently count segments crossing each edge. A segment crossing both counts in both categories; summing them is not a count of unique discarded segments.
- `usable_segment_count` counts distinct original segment indexes represented in sentence spans. `usable_span_count` counts actual trimmed nonempty sentence spans produced by the unchanged builder. `context_text_units` is the sum of each span's UTF-16 length plus one, exactly the existing 24000-unit limit. Evidence is measured before oversized rejection.
- `frozen_context=true` means the measured spans were persisted as this attempt's context. Otherwise counts describe attempted preparation, not a submitted model request. A stale source/revision can still be frozen under existing behavior; the reason records that observation without changing policy.
- `preparation_elapsed_ms` uses monotonic time through the preparation exit, including existing scheduling/callback work.

Preparation never adds an AI request, changes segment selection/sentence splitting, expands a window, or initiates new retranscription behavior. The builder observer is optional and does not alter default callers. Existing frozen JSON, excerpts and user-facing outcomes are preserved.

## Validation

Core TDD first failed for the missing measurement API, then passed after implementation. Synthetic Robolectric producer tests cover absent/partial coverage, complete empty spans, crossing boundaries, oversized input, invalid capture/range bounds, changed source/revision, unknown duration and successful frozen context. Additional cases cover online exclusion, mismatched transcript fingerprints, superseded writes, callback failures after freezing, cancellation, exception-message redaction, exhausted routine admission, disabled consent and a reproduced consent-toggle race. They assert one admitted summary per preparation and exact equality with persisted context counts, no AI calls and no serialized private fixture content. Content-free actual Sentry envelopes are written under ignored `app/build/test-context-fixtures` for the #38 consumer tests.

## Risk assessment after dependency integration

The effective risk-based policy in AGENTS.md applies to this change. The #36 delta observes existing preparation: it retains source exclusion, context windows, segmentation, size limits, attempt/state write guards, stored context and enqueue decisions. Additional numeric counting, source/revision reads inside the existing transaction, and bounded diagnostic persistence add timing and fault-path surfaces; they do not add a database migration, change backup or sync formats, modify the owner library, or alter signing and upgrade identity.

The summary uses the established consent lock, entry-epoch rejection, redacted queue, critical admission budget and uploader. It adds no worker type, scheduling cadence, network destination, runtime permission, notification behavior, audio routing, background execution requirement or hardware-dependent operation. Tests cover exact frozen counts, superseded writes, post-persistence callback failures, cancellation, disabled/recycled consent, serialized redaction and admission under routine exhaustion. Summary admission does not establish network delivery or guarantee a report after process death.

Required evidence is full combined core/app unit tests, lint/debug assembly, script/data guards, independent review, current-main integration and successful applicable isolated emulator CI. Android runtime serialization, Room behavior and existing scheduler integration remain emulator concerns; host/Robolectric tests alone do not establish them. No physical-device-only behavior is introduced by #36, so suitable automated and emulator evidence is sufficient for this scope under the effective policy. This is not a blanket exemption for dependency changes: the inherited transcription pause fix retains its separately documented acceptance evidence and limitations.

Remaining limitations: no owner-installation upgrade, OEM power-management, real network delivery, live account or phone-content validation is claimed. Those behaviors are not changed by #36. Any later scope expansion affecting them requires renewed assessment. Cancelled CI is not a passing acceptance result. No owner device, content, live account, backend, security setting or release is touched by these checks.
