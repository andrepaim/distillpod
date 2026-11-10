# Shows organization

## Plan

User-authorized first increment, owned by the Shows organization session on
`feat/shows-organization`, starting from `a7422e9` on `origin/main`.

Keep title/author search and the cover grid. Add All / Audio / Video filters;
mixed-format shows match both media filters. Empty RSS shows match All until
episode metadata is available; recognized empty YouTube sources match Video.

Offer A–Z (the existing default), Recently played and Latest episode sorting.
Publication dates, not feed refresh timestamps, determine Latest episode.
Unknown dates/activity sort last; title and ID provide deterministic ties.
Pinned shows appear first within the current search/filter, with the chosen
sort applied within pinned and unpinned groups.

Each card has an accessible pin toggle, format label, latest known publication
date and unfinished episode count. Unfinished includes both started and
not-started episodes not marked completed. Counts describe the whole show,
using episodes currently in the library. Empty/missing-date states are explicit.

Remember format, sort and pinned feed identities in local DataStore preferences.
These preferences are installation-local and outside portable backup/sync.
No library schema, playback, queue, download, account, or release changes.
Grid/list switching, custom folders and secondary episode-state filters are
deferred from this first increment.

## Implementation and acceptance

1. Write and run failing tests for summaries, format classification, ordering,
   search/filter/pin composition, and real DataStore persistence.
2. Implement pure summary/selection logic, atomic preferences, and accessible
   controls/cards with useful empty states.
3. Exercise real UI controls, ordering, navigation, changing episode counts,
   recreation, narrow layout and large text with synthetic emulator fixtures.
   Inspect the rendered page.
4. Run relevant unit tests, lint/build, emulator acceptance, and review. Integrate
   current `origin/main`, rerun relevant combined checks, merge via PR and verify
   successful CI on the merged commit before closing the issue.

## Validation risk assessment

Low-risk UI organization and additive local preferences. No Room migration or
replacement of existing data; no hardware, permissions, background work or
audio-routing changes. Unit tests including real DataStore reopen checks and
isolated emulator UI/recreation acceptance cover the affected risks. Under the
repository risk-based policy, physical-device validation is not required for
this scope. Reassess if scope or integration changes those dependencies.

Use synthetic data, no owner-phone fixtures, live accounts or paid services.
Record tested revision, checks, results and limitations in the PR. No release
publication is included.

## Validation integration

Hosted acceptance exposed a fixture assumption that two cards were composed
without scrolling. The same failure was reproduced locally at 320×640/160dpi;
the revised test observes visual order while scrolling and explicitly reveals
card details before asserting them, including actual text at 200% font size.

The independently owned Compose test-dispatcher fix from PR #76 (`82fda97`) is
integrated into this feature branch for combined validation. The Shows fixture
uses its queued main-thread/lifecycle rule. This changes test infrastructure
only; the production risk assessment above is unchanged. Keep PR #78 draft
until #76 is merged, integrate current main again, and require successful
combined CI. Ownership of the shared test fix remains with its original session.
