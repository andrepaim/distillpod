# Video experience

Tracking: [issue #30](https://github.com/andrepaim/distillpod/issues/30). The owner approved this scope before implementation. No release publication is part of this work.

## Behavior and acceptance

1. Search supports All / Audio / Video, appends late provider results without displacing existing rows, labels channels and playlists, and retains per-provider loading/error/retry.
2. Channel links canonicalize to the latest Videos tab. Actual dates sort dated playlists; persisted source order replaces alphabetic fallback for unknown dates. Per-show channel browsing preserves latest-upload order even with mixed date availability. Refresh preserves stable episode identity, listening state, downloads and retained archive entries.
3. Startup measurements distinguish extraction from first-frame rendering. Extraction retains upstream client/manifest defaults for compatibility. The memory cache respects signed URL expiry with a one-minute margin and a 30-minute cap, retaining two minutes for URLs without expiry; rejected URLs still trigger one re-resolution.
4. Fullscreen entry forces landscape when requested explicitly, and automatic entry/exit follows display orientation when system auto-rotate is enabled. Explicit exit suppresses landscape reentry until the display returns upright. Back exits fullscreen first. System bars become transient, the picture uses aspect-fit, and the original window/orientation policy returns on exit. Inline video height is bounded in landscape so its controls remain reachable after fullscreen exit.
5. The same MediaController, media item and Android PlayerView remain attached across fullscreen changes. Overlay controls auto-hide only while playing and idle, honor accessibility timeouts, and remain visible while paused, buffering, seeking, saving or using voice. Controls include pause/play, seek, Distill, voice question and exit.
6. Fullscreen capture uses the existing durable capture path while staying in the player. Voice uses the existing permission/setup flow and session sheet; rotating or hiding controls does not create a second voice session. Existing background/audio restoration remains active.

## Compatibility and data

Local Room schema 11 adds nullable `episodes.sourceOrder`. Existing rows retain every previous column and receive a null ordering value until refresh. The exported backup transfer schema becomes 7; this build accepts older schemas 1–6 and initializes their absent ordering metadata. Older builds reject schema-7 backups rather than silently discard the new column. Channel subscriptions remain local under the unchanged cloud protocol, including if a queryless channel was included in feed approval.

No accounts, model files, real subscriptions, existing downloads, saved moments or listening state may be removed during validation. The owner trial uses an isolated fixture and restores the prior queue, current item, position, speed, volume, play/pause state and automatic-transcription preference. Its synthetic context prevents a capture from initiating downloads or generation. Voice UI tests use synthetic state and make no paid request.

## Validation record

See [PR #32](https://github.com/andrepaim/distillpod/pull/32) for tested revisions, integration status and separate automated, emulator and USB-phone receipts. Historical results in other reports do not validate later revisions.

- TDD reproduced unstable provider ranking, missing channel discovery, alphabetical unknown-date ordering, cache expiry/reuse, archive ordering, and queryless channel sync leakage before their fixes.
- Initial UI acceptance reproduced missing filters and fullscreen entry before implementation.
- Physical startup baseline, on the installed pre-change app: approximately 19.5 seconds of extraction and 20.0 seconds to the first frame, versus approximately 0.13 seconds to a cached first frame. These are individual public-network observations, not percentile or guaranteed performance claims.
- Android's first immersive-use tutorial consumes Back before app callbacks. UI acceptance dismisses that platform tutorial before testing the application's Back behavior.

Automated coverage includes 95 core tests, 396 Android JVM tests, 33 repository-script tests, Android lint and APK builds. Device cases cover source-order persistence, schema upgrades from versions 1–10, provider filters/retries, touch overlays, synthetic voice session UI, the application Back handler, rotation lock, landscape/portrait transitions, paused position/speed and background restoration. The owner acceptance uses the actual Porta dos Fundos search result, renders its video, seeks, saves a fixture-only capture, checks overlay hiding/revealing and exits fullscreen before backgrounding. The PR records whether each required execution passed on its final revision.

Public-network extraction is intermittent: the live Falha sample failed during extraction on the development emulator, and one channel-feed request failed there as well. The separate Porta dos Fundos emulator playback probe decoded video successfully. An attempted manifest-skipping optimization was removed; upstream extraction defaults are retained. Cold-start timings vary with YouTube/network conditions; the cache improvement avoids unnecessary re-extraction while valid signed streams remain usable. No live paid voice request is included in acceptance.
