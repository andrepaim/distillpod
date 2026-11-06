# DistillPod Android UI improvement proposal

> Historical design and implementation record. The delivered UI has since evolved;
> use the [current guide](../getting-started.md) and [current screenshots](../demos/README.md).
> Original observations and validation results are preserved below.

Follow-up: [full design review, November 4](full-design-review.md), including searchable collections, transcript reading and grouped settings.

November 3, 2026. Owner authorization: research, choose and implement improvements independently. The completed playback reliability work is recorded in [background playback](background-playback.md).

## Direction

Make the next listening action obvious, then give saved ideas a recognisable place. Retain DistillPod's ink, lavender and mint palette, podcast artwork and four familiar destinations. Use stronger typography, restrained tonal surfaces, consistent spacing and fewer competing buttons. The result should feel like a considered native podcast app while keeping DistillPod's transcript and saved-moment workflow visible.

## Research and observations

| Reference | Observed pattern | Decision for DistillPod |
| --- | --- | --- |
| [AntennaPod home documentation](https://antennapod.org/documentation/getting-started/home) and [official illustrated introduction](https://antennapod.org/blog/2023/05/introducing-the-home-screen) | Listening in progress, new episodes, subscriptions and downloads have distinct sections based on the listener's own library. The illustration is a historical 2023 reference, not a claim about the newest release. | Put one useful resume card before the show rail and episode feed. Keep local filters close to the feed. |
| [Pocket Casts queue documentation](https://support.pocketcasts.com/knowledge-base/up-next-continuous-playback/) | Queue access from the mini-player/player, explicit Play Next/Play Last actions and clear queue order. | Add a mini-player queue shortcut/count and distinguish the selected episode from upcoming items. Keep explicit reorder controls for accessibility. |
| [Overcast's official product page](https://overcast.fm/) | Listening controls and speech-focused features are the product's primary promise. | Give transport, timing and speed a clear hierarchy. Move transcript/analysis explanations below the main listening controls. This is an inference about our hierarchy, not a claim about Overcast's current screen layout. |
| [Android Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) | Standard Material controls supply semantics and minimum touch sizes; custom actions need adequate spacing and labels. | Use at least 48 dp action targets, honest Play/Pause labels, explicit seek labels and wrapping layouts at larger text sizes. |

The current S22 screens were inspected directly. The library begins with subscriptions while the paused episode is reduced to a text-only bottom strip. Episode cards spend space on a mostly empty second row. The player repeats secondary workflow explanations alongside its primary controls. Its rewind icon says 10 while the action actually moves 15 seconds. Several horizontal button rows can overflow at larger font sizes. Saved moments visually resemble ordinary episode cards despite being DistillPod's differentiating feature.

## Selected improvements

1. **Library hierarchy:** a “Continue listening” card with artwork, episode/show, remaining time, progress and a direct resume action. Follow with the show rail and a named episode section. Keep Latest, In progress and Downloads available in a wrapping or scrolling row. Give each filter an accurate empty state.
2. **Episode rows:** clear title/show hierarchy, readable date/duration or time remaining, a prominent play/pause control, compact download state and labelled availability. Remove empty status space and use consistent artwork sizing.
3. **Persistent mini-player:** a rounded inset surface, artwork, episode title, remaining time, a clear play/pause control and direct queue access with a count. Keep its progress line and separate tap targets.
4. **Focused full player:** artwork and titles, a labelled seek bar, explicit 15-second rewind/30-second forward controls, a dominant play/pause button and readable speed choices. Give “Distill last 60 seconds” one clear primary action. Group transcript, insights and episode details below the listening controls. Hide the global bottom navigation on the full player so transport controls have more room; Back and Queue remain explicit.
5. **Queue:** an explicit current-item section and numbered upcoming episodes with artwork and time remaining. Preserve transactional ordering and the original playback timeline. Label reorder actions with the episode title and disable boundary moves.
6. **Saved moments:** a clearer collection header and quote-led cards, with subdued context/status and source artwork. Keep source review, account/quota states and replay safeguards intact.
7. **Polish and accessibility:** consistent corner/spacing tokens, an image-error fallback, correct action labels and slider semantics, scalable text and wrapping secondary actions. Use existing artwork and native vector icons; no borrowed app assets are shipped.

## Scope and acceptance

Implement the selected visual changes through the existing Compose screens and ViewModel actions. Preserve the database, account, downloads, playback service and job behavior. Search remains the same explicit directory/RSS flow; there is no automatic recommendation or account request. Keep existing discoverable test labels where they describe the same action, and update labels that were misleading.

Verify the proposal through unit/lint/build checks, focused Compose/device navigation and action checks, actual S22 screenshots, and a large-font layout check. Cover the resume route, mini-player/queue shortcut, correct seek direction, player navigation, filter empty states and saved-moment access. Recheck the installed artifact and preserve the owner's queue and data while testing. A UI-only revision does not claim to re-run the hour of playback; the media service remains the separately validated implementation.

## Later ideas

Theme preferences, a compact/grid library setting, gesture-based queue editing, sleep timer, artwork-derived colour and a full light theme can follow a listening trial. They need additional behavior or contrast/gesture validation and are not required for this focused redesign.

## Implementation result

Implemented as **0.3.0-alpha (version code 4)**. The library now leads with a compact resume card, adapts a single followed show to a compact row, and uses a smaller rail for multiple shows. Episode cards expose offline/transcript/saved states without the old empty status row. The mini-player includes artwork, remaining time and a queue shortcut/count. The full player reserves space for transport and the Distill action, labels its 15/30-second seek controls correctly, and groups secondary tools below. Queue sections distinguish current, upcoming and earlier items. Saved moments use quotes, source context and a clearer collection hierarchy.

Secondary actions in episode/transcript/insight, language, backup, OPML and explanation screens wrap rather than compete for a single row. The full player respects the system gesture inset, and Settings reports the installed version. Existing service, persistence, account and processing behavior is retained.

Validation: 48 core and 56 app JVM tests, lint, and main/test APK assembly pass. The [S22 UI device flow](evidence/s22-ui-redesign-tests.txt) passes resume/pause, mini-player navigation, real 15/30-second seeks, speed change, queue reordering without losing position, Saved/source navigation, the scoped In progress empty state and its return to Latest, and scrollable controls at **160% text size**. The private fixture restores the owner's original queue/position/speed/volume paused, deletes its own rows/audio, and makes no AI-generation requests. The first device run exposed a test assertion that expected a non-composed lazy-list item; the assertion now scrolls to the item before checking it. The final complete run passed in 10.869 seconds.

Screenshots were inspected from the S22 at default and enlarged text sizes. This led to a more compact resume/show section and a corrected player gesture inset before the passing final run. The installed artifact and cleanup are recorded in the [UI build receipt](evidence/ui-redesign-build.json). The hour of playback remains a separate, earlier validation of the unchanged service; it was not repeated for this UI revision. Full TalkBack review, broader screen/device coverage and light-theme work remain separate acceptance work.

Final cold-start follow-up: ordinary startup exposed the lazy list retaining the show row when asynchronous data inserted the resume card above it. The fix resets a newly selected collection to its actual first item. It is included in installed **0.4.0-alpha**, whose [search revision](search-ui-proposal.md) also passes the broader library/player/queue/Saved regression. That release supersedes the intermediate 0.3.0 artifact; installation and test cleanup are complete.
