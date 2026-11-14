# Share-card sample renders

These renders are cards drawn by `ShareCardRenderer`, the on-device renderer described in [social share cards](../../social-share-cards.md). They show what the app draws for each style, look and size. They are not screenshots of the app and contain no owner data: the podcast covers are synthetic shapes painted by the test, and the wording is public sample text written for the test fixtures.

## How they are produced

`ShareCardRendererTest` renders every style (Quote, Big idea, Takeaways), look (Cover, Ember, Signal) and size (Feed 1080×1350, Story 1080×1920) for several fixtures and writes the PNGs to `app/build/share-card-samples/`:

```sh
./gradlew :app:testDebugUnitTest --tests 'dev.distillpod.android.ShareCardRendererTest'
```

File names follow `<fixture>-<style>-<look>-<size>.png`, where `idea` is the Big idea style. The fixtures are:

| Fixture | Content |
| --- | --- |
| `en`, `pt` | Ordinary English and Portuguese sample content with a cover |
| `max-en`, `max-pt` | Every field at its maximum length, with very long show and episode names |
| `longword-en`, `longword-pt` | A single word wider than the text column |
| `nocover` | The English sample without podcast artwork |
| `min`, `longnames` | Minimal content; long show and episode names with ordinary content |

The renders below are a hand-picked subset of that output. They are not committed yet: media files need an owner-reviewed checksum entry in `security/reviewed-assets.json` first. Until then, regenerate them with the command above and look in `app/build/share-card-samples/`.

## Recommended samples

| File | Demonstrates |
| --- | --- |
| `en-quote-ember-feed.png` | Quote style, Ember look: amber opening mark, Newsreader quote with the highlight in amber italic, full footer with cover, show, episode and wordmark |
| `en-quote-cover-feed.png` | Quote style, Cover look: colours taken from the cover, source at the top, footer reduced to a hairline rule and the wordmark |
| `en-idea-cover-feed.png` | Big idea style, Cover look: topic kicker, headline and pull quote with an accent bar |
| `en-takeaways-signal-feed.png` | Takeaways style, Signal look: three numbered rows on solid amber, grayscale cover thumbnail |
| `pt-quote-signal-feed.png` | Portuguese Quote style, Signal look: the highlight drawn as an inverted dark box |
| `pt-takeaways-ember-feed.png` | Portuguese Takeaways style, Ember look, with a row title wrapping to two lines |
| `en-quote-cover-story.png` | Story size (9:16) of the Cover Quote card: larger type inside the top and bottom safe areas |
| `pt-idea-ember-story.png` | Story size of a Portuguese Big idea card in the Ember look |
| `max-en-quote-ember-feed.png` | Maximum-length English quote and highlight at feed size; long show and episode names ellipsized on one line each |
| `max-pt-quote-cover-story.png` | Maximum-length Portuguese quote at Story size, Cover look from a different (red) cover; ellipsized source header |
| `nocover-quote-cover-feed.png` | Cover look requested without artwork: drawn as Ember and the footer omits the thumbnail |
| `longword-en-idea-cover-feed.png` | A single word wider than the column ("Pseudopseudohypoparathyroidism"): the headline steps down until it fits instead of being clipped |

## Limits

These renders show only what the renderer draws in a JVM unit test. They are not a record of the result screen, of a device, or of how other apps display the shared image and caption. Sharing into the real Instagram and LinkedIn apps has not been checked yet.
