# Contextual distills

A distill preserves the original tap and capture range, then selects one complete thought from bounded transcript context. The takeaway is the main content. A supporting quote is optional.

## Capture and generation

`MomentContexts` inspects 120 seconds before the tap and 15 seconds after it, clamped to the episode. If the feed omits the episode duration, a complete transcript matching the current media supplies the end boundary; partial transcripts do not. Explicit expansion permits 180 seconds before the tap. Following speech may only finish a thought already underway. Transcription prioritizes all chunks required by that range. Publisher and local Whisper transcripts remain eligible; online lookup timing remains ineligible.

Sentence candidates use the original transcript spelling and enclosing segment times. Segments extending outside the bounded context are excluded. Punctuation is a candidate boundary, not evidence of semantic completeness. Context is limited to 24,000 characters; oversized input fails without truncation.

Generation selects inclusive span IDs for a contiguous passage of at most 90 seconds intersecting the original capture. The app reconstructs text from those IDs. Quotes must be whole consecutive candidate spans within the passage, at most 1,600 characters. A takeaway is 10–2,500 characters. Neither selection nor quote checking invents word-level timestamps.

One request generates a candidate; a second reviews completeness, support, relevance, attribution, negation, comparisons and conditional framing. A supported takeaway can be published with its quote removed. A failed takeaway remains unpublished. `needs_context`, `unclear_source` and `no_speech` are content outcomes rather than technical retries. Review is a model judgment, not proof of correctness.

There are no automatic AI repair loops. Failed review requests retain the candidate for explicit retry. Generation/review call attempts, successful elapsed times, model names, prompt version and source revision are persisted locally. Provider token usage is not available through the current gateway. Failed HTTP attempts can still consume allowance; call counters measure attempted gateway requests, not provider billing.

## Storage and lifecycle

Room schema 9 adds `distillations.sourceJson`, `moment_attempts` and `moment_versions`. Capture `startMs`, `endMs` and creation time remain unchanged. Source metadata records the selected passage range, original excerpt, generation ID and review metadata. Attempt records contain the frozen numbered context and candidate/review outputs.

Improve keeps the published result visible. Successful replacements archive it. Previous versions can be restored; restoring archives the current result. Source changes, deletion, cancellation and newer attempts invalidate in-flight publication. Interrupted generation never silently repeats an account request. Backup transfer schema 6 includes attempts and version history, and still imports transfer schemas 1–5.

Post drafts keep immutable source snapshots with selected-passage attribution. Explanations now freeze their source at creation and retain it after later improvement. Legacy explanations without a snapshot retain their existing timing metadata. After improvement, the UI explains that their historical excerpt is unavailable instead of substituting the new passage.

## Sync rollout

Moment payload schema 2 adds `momentSchema` and `sourceJson` under account protocol 3. New clients read legacy moments, including successful takeaway-only records. Published snapshots sync; attempts, full context and version history are local and included in backups.

Until the account is upgraded, contextual moment uploads remain pending with `upgrade_required`; this does not erase the local result or trigger background retry loops. Unrelated progress and subscription changes can still sync. Settings offers an explicit account upgrade and explains that other installations need an updated app. Protocol 3 clients retain compatibility with pending legacy acknowledgements without changing operation identities. Rules prevent legacy writes from overwriting schema-2 moments.

Deploy and validate the updated rules before enabling protocol 3 on a production account. The feature branch does not deploy rules or upgrade any real account. Protocol downgrades are prohibited. Older clients reject protocol 3 at the account head; updates already in flight cannot overwrite new-format moment records. Queue/preferences remain supported at protocols 2 and 3.

## Validation and evaluation

Automated tests cover source references, bounds, Portuguese sentence candidates, missing coverage, review decisions, optional quotes, abstention, interrupted review, deletion, cancellation, stale transcripts, history, backup, source snapshots, migrations and sync compatibility. These tests verify enforcement, not a model's reliability.

`app/src/test/resources/moment-quality-cases.json` contains synthetic English/Portuguese calibration cases, including rejected claims, hypotheticals, attribution, comparisons, fragments, ambiguity and silence. All are marked `heldOut: false`. Personal synced captures are not committed.

`LiveMomentQualityDeviceTest` is opt-in with `allowLiveMomentQuality=true` and requires a connected test ChatGPT account. It uses an in-memory library and makes up to three requests per speech case (legacy baseline, new generation, new review). It does not regenerate the owner's saved moments. It writes `moment-quality-pairs.json` and a separate `moment-quality-key.json` to the app cache. Keep the key hidden during review. Do not run this test as an ordinary CI job.

Human review input is a JSON array with one entry per case:

```json
[{"id":"rejected_claim","preferred":"A",
  "A":{"complete":2,"faithful":2,"standalone":2,"relevant":2,"criticalFailure":false},
  "B":{"complete":1,"faithful":0,"standalone":1,"relevant":2,"criticalFailure":true}}]
```

Scores are 0 (fails), 1 (partial) or 2 (passes). Assess faithful abstention as appropriate for cases whose source cannot support an idea. Preference can be A, B or tie. A misleading reversal or removed hypothetical framing is a critical failure. Measure the quality gate with:

```sh
python3 scripts/moment_quality_report.py pairs.json key.json human-reviews.json
```

The gate requires complete assessments, no critical failures, at least 20 held-out cases, and at least 80% preference for the proposed flow on held-out cases. Ties do not count as wins. Automated passing tests do not satisfy this human quality gate.

The eight originally reviewed cloud moments provide a private baseline. Their surrounding transcripts are not part of the metadata sync, so expanded-context evaluation requires the matching local transcripts. A human preference score and live-model latency must be measured before claiming the quality target has been met.

## Feature-branch verification

Android unit/core tests, lint and debug APK/test APK assembly passed. Firestore's isolated suite passed 29 tests. Device acceptance passed migrations from schemas 1–8, takeaway-only presentation, source replay and version restoration. A separate real Android SDK test against local Auth/Firestore emulators passed the protocol-3 upgrade and cross-library restoration of a takeaway without a quote.

The live comparison test was attempted on the test emulator and skipped because it has no connected ChatGPT account. No human preference result or live-model quality claim is established. Production rules, accounts and existing cloud moments were not changed.
