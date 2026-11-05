# Social sharing redesign: two finished outputs

Date: November 5, 2026. Status: implemented and validated as a preview on `feat/social-sharing`, installed for owner testing. This supersedes the product direction in [the original design](social-sharing-design.md). The installed first version is a technical baseline, not the UX to extend.

## Why the first approach failed

The first implementation made the listener act as writer and designer. Platform selection, separate text fields, rewrite actions, layout/theme/size selectors and several export buttons created work before delivering value. Passing storage and UI tests did not establish that the experience was useful or pleasant.

The new promise is: **choose what to make and receive something ready to share.** The app makes editorial and visual decisions. Exactly two formats exist: **Quote & discussion** and **Infographic**.

## Product decisions

| Decision | Behavior |
| --- | --- |
| Two formats | Text quote with discussion; one illustrated, researched infographic image |
| Generation starts on format selection | No separate Draft with AI step, initial form or setup wizard |
| Output first | The first main screen is the finished text/image or honest generation progress |
| Autonomous defaults | Select quote, angle, language, length, research queries, visual structure, palette, illustration style and export size automatically |
| One primary next action | Share the completed result |
| Optional correction | A secondary Change action accepts one natural-language instruction |
| Research is integral to Infographic | Retrieve and read sources, connect evidence to claims, then construct the image |
| Infographic quality is a release requirement | This redesign is not complete with only simplified text generation |
| Final publication remains user-triggered | Generation can be autonomous while sharing remains an explicit action |

## The complete user flow

1. Open a saved moment and tap **Create post**.
2. A small sheet offers two large choices: **Quote & discussion** — “A thoughtful post built around this quote”; **Infographic** — “An illustrated explanation with web research.”
3. Tapping either choice starts the complete generation workflow. Brief text beneath the choices explains that generation uses the connected AI account and Infographic includes web research. No extra confirmation is required for each internal step.
4. Show progress in plain language, then the completed output. Autosave automatically.
5. The result screen has **Share**, secondary **Change**, and an overflow menu. Research sources are available through a small **Sources** link on infographic results.

Progress states: “Understanding the idea…”; for infographic, “Checking sources…” and “Creating the infographic…”. Show actual stages, no invented percentage or completion-time promise. The user can leave and return without losing work. Cancel is available while running.

**Change** opens a single prompt: “What would you like changed?” Examples in supporting copy may include “Make it shorter,” “Focus on the practical implications,” or “Make the diagram easier to understand.” Do not turn these into another row of presets. Applying the instruction automatically produces and displays a revised result; retain the previous version behind Undo. Text results additionally allow direct editing of the complete post in this same secondary view. No separate headline/quote/takeaway/caption form.

Overflow contains contextual utilities: Copy text for text; Save image and Copy description for infographic; Previous version and Delete. Avoid duplicate export actions in the main screen. Native sharing chooses the receiving app at the end, so LinkedIn/Instagram are not generation settings.

Opening an existing result does not regenerate it. The format sheet remains accessible for creating the other format; each result has independent content and history. Keep draft/result discovery within Saved and the source moment, including detached results after source deletion.

## Format 1: Quote & discussion

Deliver one complete, carefully structured text post. The quote is the anchor and the discussion adds something worth reading.

Default structure:

1. A short framing sentence when it improves comprehension.
2. One exact, strong quotation, visually separated from the discussion.
3. Two or three short paragraphs explaining the meaning, why it matters and a useful implication, qualification or tension.
4. A concise ending that follows from the argument. Do not append a generic engagement question automatically.
5. Compact podcast/episode attribution and a public episode link when available.

Target roughly 150–250 words by default, adapting downward for a simple idea. This is an editorial target, not a mandatory template or platform limit. Avoid repeating the quote in different words, motivational filler, invented first-person experiences, hashtags and clickbait. A thoughtful disagreement or limit is appropriate when grounded in the excerpt.

The text format uses the immutable saved excerpt. Web research is required for Infographic; it is not a default dependency for this text format. Avoid adding external factual claims from model memory. The writer should preserve uncertainty when the saved excerpt lacks context; automatic retrieval of adjacent transcript context remains a future enhancement.

Formatting is part of the deliverable: readable paragraph lengths, purposeful spacing, a clear quotation and restrained attribution. In the app, use rich typography and a quotation block. Export a polished plain-text representation with intact paragraphs and quotation marks, because native share targets may discard rich formatting. Do not export raw Markdown markers or decorative Unicode alphabets as a substitute for formatting. Exact bold/font rendering across social apps is not promised.

Infer output language from the moment, using a clear established user preference if present. Preserve original quote wording and language; label any requested translation explicitly. Never guess the speaker from the podcast author field. Quote membership and contextual fidelity both matter: a contiguous excerpt can still be misleading if it removes an essential qualification.

Illustrative content outline, not a fabricated source quotation:

> A brief framing observation.
>
> “The selected exact sentence from the podcast.”
>
> A paragraph explaining the argument.
>
> A paragraph developing an implication or limitation.
>
> A concise final observation.
>
> Podcast · Episode · timestamp
> Public source link

## Format 2: Infographic

Deliver one polished image that explains the idea through a visual narrative. It should feel like a carefully designed explanatory presentation: an immediate central message, meaningful illustrations, a clear reading order and a small number of evidence-backed supporting points.

The image must visually communicate relationships. This version chooses a process, cycle or comparison, illustrated with three bespoke scenes. Wider text beside the scenes keeps supporting claims readable. Concept maps and quantitative charts remain future rendering extensions. These are internal design choices, never a user-facing format menu.

A typical composition includes a concise title, a dominant explanatory visual, three or four brief supporting points, an implication or takeaway, podcast attribution and readable numbered source references. Use generous whitespace and short labels. Avoid a page of tiny text, unrelated decorative icons or a large quote with a background image. If the material is too dense, narrow the story automatically and move detail into the accompanying description.

Default export: one 1080 × 1350 PNG, with higher-resolution rendering when useful before downsampling. No size chooser. Provide a generated short description with clickable source URLs for sharing/copying and accessibility. The image must make sense independently of the caption; the caption supplies full references and optional detail. Some recipient apps will require the user to paste that description separately.

Example storyboard for a hypothetical idea about learning from feedback:

- Main visual: a clearly labeled loop from attempting a task, to observing a result, to adjusting the next attempt.
- Supporting callouts: relevant findings from actually retrieved sources, attached to the part of the loop they explain.
- Bottom: one practical implication, the podcast source and numbered research references.

This example defines the visual treatment. It does not assert research findings or supply evidence before a search has happened.

### Research workflow

1. Extract the central claim, scope and unresolved questions from the podcast passage.
2. Form focused search queries about the concept. Include a query seeking limitations or counterevidence rather than searching only for agreement.
3. Retrieve and inspect relevant source content. Prefer original research, official data and authoritative explanations appropriate to the subject. Search snippets alone do not establish a claim.
4. Aim for two to four useful sources. One strong source is preferable to padding the result with weak ones. Clearly identify what is podcast interpretation, established supporting evidence and a practical inference.
5. Build an evidence record for each proposed factual callout: claim, source URL/title, relevant supporting passage or data, source publication date where available, retrieval date and any essential qualification.
6. Reconcile disagreement. If external evidence weakens the podcast's claim, adjust the explanation and surface the limitation. Never quietly rewrite the original quote or imply corroboration that was not found.
7. Supply only the verified brief to visual generation, then check the finished result against that brief.

Avoid unsupported numbers, causal claims drawn from correlations, or axes/scales invented to make a graphic look scientific. Charts require actual data with units, population and period preserved. Label a conceptual diagram as conceptual when it could otherwise look quantitative.

Source titles (or domains when the provider supplies no title) and links appear in the Sources view with claim-level references. The image carries legible short references, and its description carries full URLs. A web citation must refer to a source actually consulted. Reading a page does not permit reproducing its entire text or reusing arbitrary web imagery; create original visuals and keep supporting quotations short.

### Visual production

Use a semantic visual brief containing the takeaway, verified claims, chosen narrative structure, exact labels, visual relationships and source markers. The model decides the layout and illustration direction automatically.

Selected implementation after the capability probe: AI-designed vector illustrations integrated with a compositor that draws exact labels, claims and source markers. Extend rendering to genuine diagrams and multi-part explanatory scenes; the existing quote-card renderer cannot deliver this requirement. Generated imagery should carry explanatory meaning rather than serve as wallpaper.

The account probe rules out the hosted raster image tool for this connection; the decision and returned error are recorded below. This implementation uses a constrained vector drawing vocabulary and deterministic typesetting, so lettering does not depend on image-model spelling or OCR. No technical rendering choices are exposed to the user.

Automated review checks source fidelity, evidence alignment, spelling, text fit, contrast and visual hierarchy at phone viewing size. Allow one bounded repair pass. If the result still fails, keep the previous good version and present a concise retry state; never silently substitute the old quote-card format and call it an infographic.

## Autonomy, cost and recovery

A format choice initiates the full job. Save stage outputs so returning to a result, sharing again or changing visual style does not repeat research unnecessarily. Research-changing requests refresh the relevant evidence; a request to improve colors should reuse it.

Internal limits: one hosted research response requesting 2–4 primary sources and their limitations (300-second timeout and a bounded 64 MB stream), one initial illustration specification and one repair attempt. The hosted tool controls its individual search/read calls; this integration does not claim a precise per-call cap. Adjust after measured trials; users do not configure these budgets. Do not retry authorization/quota failures automatically. Interrupted or uncertain provider requests require a deliberate retry to avoid duplicate usage. Display plan-limit/network errors in plain language, preserving the last completed artifact.

For Infographic, a completed result must contain actual retrieved evidence. If research is unavailable or inadequate, explain that briefly and offer Retry; Quote & discussion remains the other format. Do not present memory-based assertions as web-researched support.

No transcript: Create post stays disabled until the saved moment has a prepared excerpt; the existing preparation action remains on the moment screen. If the moment contains no meaningful speech, explain why generation cannot proceed. Treat source text and webpages as untrusted data. Send public-topic search queries rather than private notes, credentials or entire listening histories.

## Provider feasibility must be settled first

The current `AiGateway`, `SubscriptionProvider` and `ResponseStream` implement text requests/results. They do not yet retain web-search source annotations, image outputs or typed tool events.

Official OpenAI documentation describes Responses web search with citations and source information, and image generation with image output. Those features require explicit integration; they are not enabled by the current text gateway. The sources examined do not establish their availability through this app's specific subscription OAuth connection. [Web search documentation](https://developers.openai.com/api/docs/guides/tools-web-search), [image generation documentation](https://developers.openai.com/api/docs/guides/tools-image-generation).

First implementation task: verify real web-search and image-generation support with the existing connection, including returned evidence, output formats and usage behavior. Do not infer tool entitlement from the text-model catalog. If unsupported, resolve the provider/access path explicitly before promising the redesigned feature. A separately billed API route is a product decision; it must not be activated as an invisible fallback. Planning this redesign does not run live account requests or change billing.

## Implementation design

Preserve useful foundations: immutable source snapshots, quote checks, revision protection, native sharing and the existing local library. Replace the editor interaction and generation model.

| Component | Change |
| --- | --- |
| Entry/result UI | Two-choice creation sheet; progress; finished artifact with Share, Change and overflow |
| Text composer | Generate one complete discussion document with source attribution, replacing separate field-first composition |
| Research gateway | Typed source references and tool results; bounded search/read workflow |
| Evidence store | Claim-to-source mapping, dates, excerpts/data and qualification metadata |
| Infographic planner | Generate a semantic visual brief from the source and verified evidence |
| Visual generator/compositor | Original illustrations, meaningful diagrams, exact typography and readable source references |
| Job coordinator | Durable stages, cancellation, source/request hashes, bounded retries and stale-result rejection |
| Result repository | Format, source snapshot, completed artifact, current/previous revision, generation metadata and evidence references |
| Asset store/backup | Owned image files with hashes, atomic writes, bounded archive transfer and recovery |

A new versioned result model uses format `discussion` or `infographic`. Completed content is immutable by revision; Change produces a new revision, switches to it on success, and retains one previous revision for undo. Pending attempts never overwrite a completed result. Background execution uses the existing Android work infrastructure with persisted stage state; no restart should silently reissue an uncertain paid request.

Existing drafts remain accessible and can be shared as saved. Their original content is not silently rewritten or deleted. Offer the same two creation choices when rebuilding from the moment. Legacy text can be shown as an editable text result; old quote-card images must be labeled as legacy, never migrated into researched infographic results. Preserve old backups and schema compatibility with explicit versioned migrations.

Infographic images and evidence are durable authored assets, distinct from temporary share exports. Add archive entries with hashes, bounded decoding/extraction and retention accounting; the current JSON-only backup cannot recover generated images. Restore a completed infographic offline without regenerating it. Reuse the existing FileProvider handoff for exported copies. Keep cloud draft/asset sync outside this redesign unless separately designed.

## Delivery sequence and gates

| Step | Deliverable | Gate |
| --- | --- | --- |
| 1 | Verify search/image capabilities; produce sample outputs from three representative real moments | Both formats are feasible through an explicit supported access path |
| 2 | Establish output quality: three text posts and three illustrative infographics, including Portuguese | Readable, thoughtful, grounded outputs with no user setup; review actual outputs before expanding UI work |
| 3 | Build the two-choice flow and autonomous text generation | Format selection immediately generates; no mandatory forms or design decisions |
| 4 | Implement research, visual planning, generation and verification | Real sources support infographic claims; visual structure explains relationships |
| 5 | Add Change/Undo, durable job recovery, legacy-draft migration and asset backup | Completed results survive interruption, deletion of the original moment and restore |
| 6 | Test on the owner's phone and real share destinations | The common flow is select format → review → share, with no necessary editing |

The first review should inspect the actual text and infographic outputs, not a polished settings screen. A researched infographic is part of this release's completion gate; do not declare the redesign complete after shipping only the text half. Provider feasibility determines the final effort estimate. Do not carry forward the earlier estimate for a text-and-template MVP.

## Acceptance criteria

- Exactly two creation choices and no required input fields after selecting a moment.
- From an open moment, three app-level actions reach sharing: Create post, choose format, Share. Selecting a native destination/final publication is separate.
- No platform, language, theme, size, layout or AI-action selectors in the main flow.
- The text result contains an accurate quote and substantive discussion, with readable exported paragraph structure.
- The infographic explains an idea visually and contains useful evidence from actual web retrieval, traceable to its sources.
- Numbers, labels, quotes and source references survive final image rendering exactly; there is no clipped or illegible footer.
- An ordinary listener can understand the infographic at phone viewing size without zooming or reading the caption first.
- Change accepts a natural-language instruction; users can undo without regeneration.
- Existing drafts and backups remain usable. Completed images, sources and revisions survive restore.
- Interrupted jobs and late responses cannot lose or replace a completed result. Reopening/sharing does not consume new generation allowance.
- Test long/ambiguous quotes, weak or conflicting evidence, missing transcripts, both languages, network/quota failures and image overflow.
- Product acceptance includes observed use on the phone and review of actual output quality. Automated test counts alone are insufficient.

## Planning status

The product plan above was written before implementation. The executable sequence and capability decision below record the implementation work authorized by the subsequent request.

## Executable implementation plan

This section defines the build sequence approved by the owner's “Full plan, then implement it” request. Implementation starts from commit `93a2238` on `feat/social-sharing`, using an isolated checkout to avoid concurrent Android Auto edits.

### 1. Establish the provider contract

Run an opt-in live-account probe with public sample prompts only. Verify web-search completion, consulted/cited sources and image output separately; retain sanitized capability evidence and never log credentials. Implement a typed gateway that distinguishes successful researched output from a text-only completion. Parse tool events, citations and provider failures with bounded input/output sizes. Cancellation must reach the underlying HTTP call. Tests use injected responses and malformed/error/oversized fixtures.

If the connected account does not provide a necessary capability, report the exact result, complete independent work, and identify the remaining external dependency. Do not label a mocked or unresearched image as a completed implementation.

### 2. Define versioned results and durable operations

Keep original `post_drafts` source relationships. Add a versioned result envelope containing format, source snapshot, completed artifact, previous artifact, pending instruction, attempt ID, revision, stage and sanitized failure. Store research as typed source records and claim references. Store images by content hash in private app files; the result refers to an asset, never an arbitrary filesystem path.

Use Room transactions for stage/revision changes and WorkManager for generation. Each job accepts only its active attempt ID; stale requests cannot publish into edited, canceled or deleted results. Save successful stage outputs before progressing. A stopped job with an uncertain provider outcome becomes manually retryable. Explicit Change queues a new attempt while the existing result remains shareable. Undo restores the previous completed artifact without network access.

### 3. Produce the text format

Select a strong source quote, generate one complete discussion document, infer language, and append authoritative attribution outside model output. Normalize paragraph formatting for sharing. Validate the exact quote against the source, and check that the discussion adds explanation rather than repeating it. One repair attempt is allowed for invalid structured output. Preserve source text while allowing whole-document direct editing through the secondary Change view.

### 4. Produce the infographic format

Derive a public research question from the excerpt; inspect retrieved sources and qualifications. Assemble the evidence-backed brief and choose a visual narrative. Produce illustrations and explanatory diagrams, compose exact labels and citations, and check the final image at phone size. Persist the completed PNG and its source description. Regeneration caused by visual-only instructions reuses current research when still relevant; factual changes trigger new research.

The release gate requires actual retrieved evidence and an illustrative explanation. Sample fixtures test layout and failure paths; live public examples establish the end-to-end capability. All figures and evidence callouts must map to retrieved sources. Failure retains the prior artifact and offers a concise retry path.

### 5. Replace the UI

Create post opens a sheet with exactly Quote & discussion and Infographic. Selection creates and queues a result, then opens its progress screen. Completion replaces progress automatically. The main actions are Share, Change and contextual overflow; Sources is a small link for research-backed results. Remove platform/theme/size/layout selectors and separate content fields from this path. Keep the existing drafts accessible with explicit legacy handling.

Changing a result displays the new version directly and offers Undo. Share dispatches the actual output type: discussion text or infographic PNG with source description. Copy/save utilities are secondary. Preserve paragraph structure across text exports and handle recipients that omit captions.

### 6. Make image results portable

Extend backup snapshots with bounded asset entries and a manifest of hashes; keep old JSON snapshots and existing history files readable. Validate asset names, byte counts, hashes and dimensions before accepting restored results. Restore completed images offline. Do not encode large images directly into the existing JSON tables. Failed restores preserve the current library, and temporary exports remain excluded. Immutable image blobs are installed before the database transaction commits; a failed merge can leave an unreferenced blob but cannot delete a concurrent asset or commit a missing-image reference. The complete expanded snapshot is capped at 32 MB, each image at 8 MB, and each snapshot at 100 images.

### 7. Validate and deliver

Run focused tests for tool parsing, citation provenance, quote checks, state transitions, cancellation, stale responses, revisions/undo, legacy migration, assets and backup restoration. Run the established complete JVM suites, lint and APK builds. Exercise the two-choice flow on an isolated emulator at normal/enlarged text sizes, with fake gateways for deterministic UI tests. Review representative EN/PT text and infographic outputs for substance, visual explanation and source fidelity.

Keep a validation receipt that separates fixture checks, live provider checks, device behavior and still-unverified external handoffs. Commit only the social-sharing changes. Prepare the compatible signed phone build with existing Firebase settings after acceptance; use in-place updates for the owner's test installation.

### Capability decision after the live account test (2026-11-05)

The existing subscription connection successfully executed `web_search`. It explicitly rejected `image_generation` with `subscription_sharing_unsupported_capability`: subscription sharing supports web search and developer-defined tools, but requires an API key for the image tool. The initial malformed probe was corrected to use the account's required message-array input and instructions; this was not an account entitlement failure for web search.

The implementation therefore uses AI-designed vector illustrations and relationship diagrams, rendered locally with exact typography and source references. This still produces the requested illustrated infographic, with real researched evidence, without adding a provider setup flow. The AI writes a bounded drawing specification for three bespoke explanatory scenes (circles, rectangles, polygons, lines); no scripts, remote assets, text inside artwork or arbitrary SVG execution are permitted. The app validates bounds and paints those scenes alongside the reviewed claims. Raster image-tool integration is not advertised as available, and no API-key billing fallback is used. Real visual samples must be reviewed before this is considered polished enough for release.


### Implementation result

The two-format preview is implemented. The completed UI, storage/recovery path, native sharing and binary image backups are covered by 85 core and 191 app JVM tests, release lint, APK builds and an API 36 large-text UI acceptance test. Three public examples produced quote discussions and researched illustrated infographics, including Portuguese. Real samples exposed and resolved text-fit, provider citation-format and repair-prompt issues.

The compatible `0.10.1-alpha.2-social-preview` build is installed on the owner's Galaxy S22 Ultra with the previous signing certificate and Firebase configuration. No owner-library test fixtures were inserted. Actual publication in social apps remains user-driven and was not performed. See the [validation receipt and examples](evidence/social-redesign/validation.md).
