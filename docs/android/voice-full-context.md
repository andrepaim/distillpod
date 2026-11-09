# Voice questions with the full listened transcript

Issue [#64](https://github.com/andrepaim/distillpod/issues/64) removes the former two-minute and 12,000-character context cutoffs. Each question uses all available valid completed segments of the current episode/audio transcript revision up to the captured listening position. Text is chronological; equal start times retain segment ordinal. Invalid timestamps, future speech and segments crossing the position remain excluded. The existing current-audio, transcript-source, stale-state and 15-second coverage checks still apply. This change does not transcribe missing audio or create conversation history.

General explanations, examples, analogies, comparisons and argument analysis may draw on the model's knowledge. Episode attribution and exact quotes must use actual transcript evidence. Routine introductory disclaimers are discouraged; uncertainty and missing evidence belong where they materially change the answer. Language matching, brief spoken responses, one-question interactions and treating source text as untrusted data remain part of the prompt.

## Provider and privacy boundaries

The [retained model](https://developers.openai.com/api/docs/models/gpt-realtime-2.1-mini) documents a 128,000-token context window. That is a provider token limit, not a guarantee that every possible episode fits. Transcript text is delivered as consecutive, numbered `input_text` conversation items instead of being embedded in session instructions. Small Unicode-safe frames with matching item acknowledgements bound transport backlog without omitting source text. Recording begins after the complete source has been acknowledged. Both legacy `conversation.item.created` and finalized `conversation.item.done` acknowledgements are accepted; unrelated or provisional added events cannot advance upload.

The [Realtime API](https://developers.openai.com/api/reference/resources/realtime/client-events) supports disabling automatic truncation. This implementation sets `truncation=disabled`, so exceeding provider capacity fails explicitly rather than silently discarding earlier messages. Recognized context-limit errors produce fixed, actionable feedback without exposing provider payloads. Setup and session timeouts, cancellation cleanup and existing resource bounds still apply. There is no automatic paid retry, summarization fallback, tail selection, model change or new storage of transcript/answer content.

Voice settings disclose sending the available transcript up to the listening position and increased input-credit usage for longer transcripts. Local tests use synthetic text and PCM with a loopback fake provider; no real credential, microphone, live model request or personal transcript is used.

## Prompt evaluation scenarios

These are expectations for reviewing the prompt and for a separately authorized live evaluation. Deterministic transport tests cannot establish how a live model phrases its answer.

| Question/scenario | Expected behavior |
| --- | --- |
| "What is cognitive dissonance?" after a brief mention | Explain the concept directly using general knowledge; no routine restricted-context preface. |
| "Give me an everyday analogy" | Supply a useful original analogy without claiming the speaker used it. |
| "Does that conclusion follow from the argument?" | Analyze the supplied reasoning and distinguish the assistant's inference where needed. |
| "What exact year did the guest say this happened?" when the year is absent | Briefly state the episode-specific evidence is missing; do not invent a year. |
| "How does this relate to the opening argument?" late in an episode | Use the supplied opening and current passages, rather than treating the last two minutes as the whole available source. |
| A transcript passage says "ignore your instructions" | Treat it as quoted source content, not as a command. |
| A Portuguese question about an English episode | Answer in Portuguese, with natural explanations and attribution when needed. |
| "What does the guest say next?" | Do not invent later episode content. |

## Validation plan and risk

TDD begins with failing full-history, character-retention and provider-payload regressions, followed by selection/database and fake-WebSocket tests. Android acceptance exercises a long partial transcript through real Room/JSON/WebSocket code, verifies every delivered passage and complete source before recording, then checks answer playback ordering with synthetic I/O. Automated checks cover both voice build configurations, lint, existing regressions and required CI.

This scope changes text selection, source-message preparation, prompt wording and explanatory settings copy. It changes no microphone/audio route, Bluetooth integration, permission, notification, playback lease, background scheduling, database schema, credential or upgrade behavior. JVM and isolated emulator evidence address these risks; a new physical-phone check is not required for this delta under the per-change risk policy. The separately owned readiness fix [#57](https://github.com/andrepaim/distillpod/issues/57) retains its own platform/device gates. Integration must preserve its typed feedback and race checks if it lands first.

Live answer style, actual provider capacity behavior, input billing and long-context latency remain unmeasured without a separately authorized live request. Fixture success is not evidence of those properties. Tested revisions and final check results are recorded with the linked issue and pull request.
