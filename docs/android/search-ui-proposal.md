# Search and discovery UI proposal

November 3, 2026. Requested scope: research podcast-player screenshots, propose a professional search experience, implement and validate autonomously. This builds on the [general UI redesign](ui-improvement-proposal.md).

## References and assessment

| Reference | Useful pattern | Adaptation |
| --- | --- | --- |
| [Pocket Casts' search announcement and official screenshots](https://blog.pocketcasts.com/2023/05/02/pocket-casts-search/) | A strong search entry, artwork beside results, and readable distinctions between result types. The illustrated announcement is from 2023. | Use a compact, persistent search field and artwork-led podcast results. DistillPod searches shows; episode-search tabs will not imply an unsupported capability. |
| [AntennaPod's subscription guidance](https://antennapod.org/documentation/getting-started/subscribe) and [official screenshots](https://antennapod.org/media-kit/) | Search, suggested discovery and direct RSS entry are separate ways to find a show. | Make topic searches useful on the initial screen, and give RSS its own explicit entry sheet while retaining URL support in the main field. |
| [Spotify's search guidance](https://support.spotify.com/us/article/search/) | Search supports names and broader subject discovery. | Provide topic shortcuts as ordinary user-triggered searches, without claiming that these are trending shows or personalised recommendations. |

The current screen has a small thumbnail slot but weak hierarchy, two large stacked action buttons, no useful initial browse state, no show preview and an ambiguous empty state. Results are not associated with their submitted query. The repository also accepts an empty high-resolution artwork string without checking a valid smaller fallback.

## Selected design

1. A persistent rounded search field with a search icon, clear control, keyboard Search action and visible submit button. Names, creators and topics remain the primary path.
2. A useful initial screen: up to six recent successful keyword searches stored locally, a Clear action, and a compact two-column topic grid. A topic tile runs a real directory query only when tapped. RSS URLs are excluded from history.
3. A prominent first result with a larger real cover, followed by compact thumbnail rows. Preserve directory order and label the first result “Top result”; do not invent popularity, ratings, subscriber counts or endorsements.
4. Publisher/title/category hierarchy, distinct Follow/Following/Open states and per-show loading feedback. Actual provider images are preferred; a designed monogram fallback remains when artwork is missing or fails.
5. Tap a result to preview its artwork and metadata in a bottom sheet before following. A followed show can open directly. Previewing does not subscribe or request AI work.
6. Separate initial, searching, no-results and error/retry states, each tied to the submitted query. Editing or replacing a query cancels the old request and prevents a late response from replacing current results.
7. A dedicated RSS sheet with a clear Add action and retained input on failure. Keep interest-based AI discovery as a secondary, explicitly requested path.
8. Match the existing ink/lavender/mint design, use at least 48 dp action targets, support enlarged text and keep essential search controls available while the result list scrolls.

## Scope and verification

Use the existing public directory and feed subscription repository. Add category metadata and robust artwork fallback parsing, but retain feed deduplication and existing subscription identities. Recent keyword searches stay on the device and can be cleared. Search and preview never send listening history or issue AI-generation requests.

Verify late-response handling, loading/error/empty states, history limits and artwork fallback in focused tests. Exercise search, clear/retry, actual thumbnail loading, preview and Follow/Open callbacks through Compose/device checks, including 160% text. Inspect screenshots with real public directory results and verify the installed artifact. Preserve the owner's library, playback queue, credentials and existing models.

## Result

Delivered as **0.4.0-alpha, version code 5**, installed on the owner's S22 Ultra. The compact search field stays above the scrolling list. Results use real publisher thumbnails, a larger first match, creator/category metadata, show previews and explicit Follow/Open states. Missing or failed artwork keeps a monogram fallback. Topic tiles, clearable local recent searches, RSS entry and the existing opt-in interest flow complete the initial screen.

Editing/replacing a query cancels both its coroutine and underlying OkHttp call. Request generations additionally prevent obsolete results/errors from replacing newer state. Initial, loading, no-results and retry states are distinct. Recent successful keyword searches are capped at six and deduplicated without regard to case; RSS URLs are excluded. Public integration checks restore the owner's original history.

Validation passes **110 JVM tests** (48 core, 62 app), lint and both APK assemblies. Six focused new tests cover network cancellation, obsolete responses, error/empty/retry state, artwork fallback/category parsing, and actual local history storage/clearing. The [three S22 acceptance flows](evidence/s22-search-ui-tests.txt) cover rendered thumbnail pixels, preview, real feed Follow/Open callbacks, no-result/error/retry, recent clearing, RSS entry, enlarged text, a public Naruhodo query and the existing player/queue/Saved regression. All three passed in 26.249 seconds.

Both search flows additionally pass with **Android's actual system font scale at 160%**, including the modal sheets. [Those checks](evidence/s22-search-system-font-tests.txt) passed in 15.917 seconds; the owner's original font setting was restored. Real publisher-artwork and settled preview screenshots were visually inspected. The controlled fixture verifies decoded image pixels rather than merely checking for an image URL.

The [installed-build receipt](evidence/search-ui-build.json) records the matching APK hash and cleanup. Test subscription/audio, recovery files, diagnostic screenshots and the test package were removed. Owner library, queue, credentials and models remain. These checks requested no AI generation. The prior library cold-start visibility fix is included and passes the broader UI regression. The unchanged media service retains its separate hour-test evidence; that hour was not repeated for this UI revision.

A final visual pass shortened the empty search hint to a single line. That copy-only revision was rebuilt, passed lint, installed and checked on the normal S22 browse screen. The receipt distinguishes the full-acceptance APK hash from the final installed hash; search behavior is unchanged.
