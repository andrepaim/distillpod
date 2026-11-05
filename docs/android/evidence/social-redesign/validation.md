# Social redesign validation

Date: 2026-11-05. Baseline: `93a2238`, branch `feat/social-sharing`.

The implementation replaces the form editor with two format choices and a saved result screen. Publication remains in the receiving app.

## Automated and device checks

- 85 core and 191 Android JVM tests passed, including source fidelity, attempt guards, cancellation, revisions, undo, legacy content, immutable images, corrupt/missing assets, backup-history roundtrip and content-URI sharing.
- Release lint and debug/release APK builds.
- API 36 disposable emulator: two choices, Share/Change, absence of old editor controls, Change dialog, activity recreation, source deletion, at 1.6× font scale. Screenshots use explicitly identified fixture text.
- Physical Galaxy S22 Ultra: compatible in-place installation, same signing certificate and Firebase configuration. No test creates or deletes owner-library records.

## Live output checks

All live inputs are public, self-authored validation samples. The provider never receives listening history from these tests. Production composition, research, source parsing, illustration specifications and rendering are exercised.

- Learning/recall: exact-quote discussion and four retrieved sources, including counterevidence. The first narrow-column layout rejected overflow; the revised renderer creates readable illustrated sections from the recorded live brief and artwork.
- Portuguese attention/task switching: a coherent quote discussion and five tool-attested references, with three selected claims/sources in the image. Both formats completed. Ordinary Markdown references exposed a provider variant without structured annotation objects; the parser now accepts only links independently reported by search/open actions. Regression tests reject model-only invented links.
- English feedback sample: research completed with four sources. The initial brief exceeded text limits; the bounded repair now receives the invalid draft and the exact validation error. A regression test verifies that the repair shortens the affected field and preserves the cited claim. Both formats subsequently completed; the final image was visually reviewed.

The existing OAuth connection supports `web_search`; it rejects the hosted `image_generation` tool with `subscription_sharing_unsupported_capability`. Infographics therefore use AI-designed vector scenes, with app-rendered typography and references. No API key or separate billing fallback is configured.

The final illustrated layout was visually inspected across all three samples for English and Portuguese text fit, distinct scenes, readable source markers and preserved caveats.

## Practical limits

Native share intents and PNG access are tested. Actual publication in LinkedIn/Instagram and the recipients' caption handling are not automated or claimed. Posting is always the user's action. Infographic visual quality is still a preview for owner feedback; the renderer supports process, cycle and comparison with three illustrated sections. Older results remain readable.

Snapshots include referenced current/previous images in a bounded binary wrapper, with SHA-256 and dimension validation. The 32 MB expanded snapshot limit remains; very large libraries receive an explicit size error. A failed transactional merge may leave an immutable unreferenced image blob, while existing library records remain intact.
