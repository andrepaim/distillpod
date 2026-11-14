# DistillPod design system proposal: Ink & Ember

Status: **accepted 14 Nov 2026**. The owner chose the Ember palette, bundled fonts and icon A. Delivery is tracked in the [implementation plan](design-system-plan.md).
Date: 14 Nov 2026.
Basis: a design review of `main @ 4f6aca8` plus a navigation-only screen walk of 0.24.0-alpha.1 on the S22 Ultra. Those screenshots are local only and are not committed.

The visual report, with annotated screens, palettes, mockups and icon concepts, was reviewed locally. It is not committed because it contains owner-device screenshots.

## 1. Problem

The app's capability is distinctive, but its surface is not. The review found four causes:

1. **The colour scheme is only partly defined.** `DistillTheme` (`features/ProductApp.kt`) sets 11 of Material 3's colour roles. Every other role falls back to the M3 baseline purple. Pixel samples from the phone confirm two of these leaks, and the other two are the documented M3 defaults for components whose roles are unset:
   - `#4A4458` (`secondaryContainer`) on selected chips, the nav indicator and tonal play buttons;
   - `#36343B` (`surfaceContainerHighest`) on info cards and switch tracks;
   - `#2B2930` on dialogs (inferred);
   - `#211F26` on menus (inferred).

   These mauve greys sit beside the app's blue-slate surfaces, so the palette feels muddy.
2. **Three accents with no rule.** Lavender is used for everything interactive, mint for show names and some statuses, and amber for warnings. The lavender `#B7B9FF` is close to M3's baseline primary `#D0BCFF` and to Apple Podcasts' purple, so the app reads as a template.
3. **Too many buttons.** The source has 115 `TextButton`, 57 `OutlinedButton` and 34 filled `Button` calls, against 22 `IconButton`. It has 0 `ListItem`, 1 `SegmentedButton`, 18 `AlertDialog` and 5 bottom sheets.
   - The player has three filled primary actions and is about 2.3 screens tall.
   - The Episode page uses four button styles for peer actions.
4. **Flat hierarchy and dense copy.**
   - Six different section headings use `titleLarge` SemiBold, the same weight as the top bar.
   - Explanations are shown inline as paragraphs. Library sync opens with about 140 words.

What is already good and must be kept:
- the content-first layout and real artwork;
- the four-tab structure and the floating mini-player;
- the serif quote cards;
- the accessibility work: semantics, 48 dp targets, 160% text handling and TalkBack custom actions.

## 2. Principles

1. **Artwork is the colour.** Chrome stays near-neutral. The player and episode hero borrow a dark tint from the cover art.
2. **One accent, reserved.** The accent marks the Distill action, saved-moment markers, the selected state and at most one filled CTA per screen.
3. **The button budget.**
   - At most **one filled button** per screen.
   - Secondary actions are icon buttons with short labels in an action row.
   - Everything else goes in an overflow menu or a sheet.
   - Text buttons are only for inline links ("See all", "Show more") and are never stacked.
4. **Sheets over dialogs.** Choices that keep context (speed, sleep timer, confirmations, "Learn more") use `ModalBottomSheet`.
5. **Read like a page.** Long-form text (transcripts, insights, quotes) uses an editorial serif at reading measure.
6. **State over explanation.** Show status ("Ready · on this phone", "Synced 2 min ago"). Put explanations behind an ⓘ.

## 3. Tokens

All tokens live in a new package `dev.distillpod.android.ui.theme`:
- `DistillColors.kt`
- `DistillType.kt`
- `DistillShapes.kt`
- `DistillSpacing.kt`
- `DistillTheme.kt`

`ProductApp.kt` and `ListeningDesign.kt` stop defining colours. A unit test asserts that every `ColorScheme` role is set explicitly, so nothing can fall back to the baseline again.

### 3.1 Colour: dark, the default

| Token | M3 role(s) | Ember (recommended) | Mint (alt) | Indigo (alt) |
|---|---|---|---|---|
| bg | background, surfaceDim | `#0F0E0D` | `#0E1012` | `#0D0E14` |
| s0 | surfaceContainerLowest | `#0B0A09` | `#0A0C0D` | `#09090F` |
| s1 | surface (cards), surfaceContainerLow, navigation bar | `#191816` | `#161A1C` | `#15161F` |
| s2 | surfaceContainer, cards, sheets, menus | `#22201D` | `#1E2326` | `#1D1F2B` |
| s3 | surfaceContainerHigh, mini-player, dialogs | `#2C2925` | `#283033` | `#272A38` |
| s4 | surfaceContainerHighest, surfaceVariant, switch track | `#36322D` | `#323B3E` | `#313445` |
| surfaceBright | surfaceBright | `#3D3933` | `#384245` | `#383B4D` |
| text | onBackground, onSurface | `#F2EEE8` | `#EEF3F2` | `#EEEFF6` |
| muted | onSurfaceVariant | `#ABA397` | `#A3B1B0` | `#A9ADC2` |
| outline | outline (≥3:1 on s2) | `#7A7266` | `#6E7B7A` | `#737891` |
| outlineVariant | dividers | `#3A3631` | `#343D40` | `#353849` |
| accent | primary, surfaceTint | `#FFB547` | `#59D8B5` | `#9C8CFF` |
| onAccent | onPrimary | `#2A1800` | `#00261D` | `#160C4A` |
| accentContainer | primaryContainer, secondaryContainer | `#3B2A12` | `#10372E` | `#2B2560` |
| onAccentContainer | onPrimaryContainer, onSecondaryContainer | `#FFDDB0` | `#B4F2E0` | `#E2DDFF` |
| secondary | secondary (neutral, not a 2nd hue) | `#D6CCBE` | `#C5D3D1` | `#C8CBE0` |
| onSecondary | onSecondary | `#2A251E` | `#1D2726` | `#24263A` |
| tertiary / warning | tertiary | `#FF8A65` | `#FF8A65` | `#FF8A65` |
| tertiaryContainer | tertiaryContainer | `#4A1F10` | `#4A1F10` | `#4A1F10` |
| error | error | `#FF6B6B` | `#FF6B6B` | `#FF6B6B` |
| errorContainer | errorContainer | `#5C1A1A` | `#5C1A1A` | `#5C1A1A` |
| success | custom `DistillExtras.success` | `#7BD88F` | `#7BD88F` | `#7BD88F` |
| inverseSurface / inverseOnSurface | snackbars | `#F2EEE8` / `#22201D` | `#EEF3F2` / `#1E2326` | `#EEEFF6` / `#1D1F2B` |
| inversePrimary | inversePrimary | `#8A5300` | `#006B54` | `#5B4BD6` |
| scrim | scrim | `#000000` | `#000000` | `#000000` |

Measured contrast for Ember:
- accent on bg: 11.0:1
- onAccent on accent: 9.7:1
- text on s1: 15.4:1
- muted on s1: 7.1:1
- muted on s3: 5.8:1
- outline on bg: 4.1:1
- outline on s2: 3.4:1
- warning on s1: 7.7:1
- error on s1: 6.4:1
- success on s1: 10.2:1

### 3.2 Colour: light (phase 4, Ember)

| Token | Value | Note |
|---|---|---|
| bg | `#FBF8F3` | |
| s1 / s2 / s3 | `#F4EFE7` / `#EDE6DB` / `#E4DBCE` | |
| text / muted | `#1E1A15` / `#6B6257` | 16.3:1 and 5.2:1 |
| accent / onAccent | `#A35F00` / `#FFFFFF` | 4.7:1 and 5.0:1 |
| accentContainer / onAccentContainer | `#FFDDB0` / `#331C00` | |
| outline / outlineVariant | `#8D857A` / `#D3C8B8` | 3.4:1 |
| error | `#B3261E` | 6.2:1 |

The theme follows the system dark/light setting. A Settings override (System / Dark / Light) is optional. Material You dynamic colour is **not** the default: it would erase the brand. It can be offered later as an "Use wallpaper colours" toggle.

### 3.3 Artwork tint

- Extract the dominant or vibrant swatch from the current cover with `androidx.palette:palette-ktx`. Coil already supplies the bitmap.
- Map it to HCT tone 25 and chroma ≤ 36, then use it at 85% → 0% as a radial gradient behind the player and episode hero.
- Never use the tint for text or buttons.
- Cache the result per artwork URL.
- Fall back to `accentContainer`.

### 3.4 Typography

Fonts:
- **Inter** for the interface.
- **Newsreader** for reading text.

Both are SIL OFL and are bundled in `res/font` as seven static, Latin-subset instances (Inter 400/500/600/700, Newsreader 400/500/400 italic), about 480 KB in total. The alternative is downloadable Google Fonts via `ui-text-google-fonts`, which avoids bundling but needs network on first run.

Fallback if the decision is "no bundled fonts": Roboto Flex for the interface and the system `FontFamily.Serif` for reading.

| Style | Use | Font / size / line / weight |
|---|---|---|
| displaySmall | Top bar title on root tabs (phase 3) | Inter 28/34/700, −0.5 tracking |
| headlineSmall / titleLarge | Player and episode titles; top bar | Inter 22/28/700 / 20/26/700 |
| titleMedium | Sheet titles | Inter 17/22/600 |
| titleSmall | Item titles; **section headings** in bold (was titleLarge) | Inter 15/20/600, headings 700 |
| bodyLarge | Descriptions, long body text | Inter 16/24/400 |
| bodyMedium | Body UI text | Inter 14/20/400 |
| bodySmall / labelMedium | Metadata, muted | Inter 12.5/17/400 / 12.5/16/500 |
| labelSmall | Timestamps, counters | Inter 11/14/600, tabular numerals |
| `DistillType.quote` / `quoteCompact` | Post quotes / Saved cards | Newsreader 21/28/400 / 17/23/400 |
| `DistillType.reading` | Transcript, insight and answer body | Newsreader 16/25/400 |

Rules:
- Show names in lists are **muted**, not mint or accent.
- At most one style larger than `titleSmall` per screen, excluding the top bar.

### 3.5 Shape and spacing

| Token | Value | Use |
|---|---|---|
| shape.xs | 8 dp | Status tags, small thumbnails |
| shape.sm | 12 dp | Artwork ≤ 56 dp, chips |
| shape.md | 16 dp | Cards, grouped list containers, mini-player |
| shape.lg | 24 dp | Sheets (top corners), hero artwork |
| shape.full | 50% | Play button, pills, segmented controls |

This replaces the radii in use today: 6, 10, 14, 16, 18, 20 and 24 dp.

Spacing is on a 4 dp grid:
- page gutter 16 dp
- section gap 24 dp
- item gap 12 dp
- list row height ≥ 64 dp, or 72 dp with artwork

## 4. Component rules

| Component | Rule |
|---|---|
| Filled `Button` | Primary action only, at most one per screen. Accent colour. |
| `FilledTonalButton` | State toggles with weight ("Following ✓"). `accentContainer`. |
| Icon action row | Peer actions such as download, queue and share: 40 dp tonal circle plus a 10 sp label. 48 dp touch target. |
| `TextButton` | Inline links only. Never two stacked vertically. |
| `OutlinedButton` | Avoid. Only for a secondary next to a primary in a sheet footer. |
| `ListItem` | Every navigational row: settings, the transcript, insights and Ask entries. Leading icon in a 30 dp `accentContainer` square, supporting text for status, a trailing value or chevron. |
| Grouped list | A `surfaceContainerLow` container with `shape.md`, rows separated by `outlineVariant` dividers, and an uppercase 11 sp muted group label. |
| `SegmentedButton` | Mutually exclusive view filters: Library Latest / In progress / Downloaded, Shows All / Audio / Video. |
| `FilterChip` | Multi-select filters, such as Saved filtered by show. |
| `ModalBottomSheet` | Speed and effects, sleep timer, "Learn more", queue clear confirmation, episode overflow on small screens. |
| `AlertDialog` | Destructive confirmations only (unsubscribe, delete download, disconnect account). |
| `SearchBar` | Directory search and in-list search. No separate submit button. |
| `PullToRefreshBox` | Library and show refresh. Remove the refresh top-bar icon. |
| Snackbar with Undo | Removing from the queue, marking played, deleting a download. |
| Progress | A 2–3 dp line flush to the edge of rows and the mini-player. The player scrubber carries chapter gaps and saved-moment dots. |

## 5. Screen specifications

### 5.1 Now playing (phase 1)

The player is one non-scrolling screen at default text size. At 160% text it may scroll.

The screen has these parts, top to bottom:

1. **Top bar:** collapse chevron (back), the show name (taps through to the show), overflow menu.
   - The overflow holds: Episode details, Go to show, Share, Transcribe locally again, Video fullscreen.
2. **Artwork:** about 60% of the width, `shape.lg`, with a shadow, over the artwork-tint gradient.
3. **Title and show:** title at `titleLarge` (max 2 lines), then show · date in muted text.
4. **Scrubber:**
   - The track shows chapter boundaries as 2 dp gaps and saved moments as accent dots.
   - The elapsed and remaining times use tabular numerals.
   - Keep the existing semantics (`contentDescription` and `stateDescription`).
5. **Transport row:**
   - a speed pill ("1.25×"), which opens the speed sheet;
   - −10;
   - **Play/Pause**, a 66 dp circle in the `onSurface` colour with a `surface` icon;
   - +30;
   - a moon icon, which opens the sleep sheet. While a timer runs it shows the remaining minutes as a badge, and long-press cancels.
6. **Action dock**, with equal height and 52 dp min:
   - **✦ Distill** is wide, filled with the accent, and calls `vm.captureMoment` with the same enabled rule.
   - **Ask** (mic) is shown only when voice is enabled.
   - **Transcript** opens the transcript at the current position.
7. **Peek sheet:** tabs for Up next, Transcript (live, follow-along), Chapters and Insights. It reuses `TranscriptTools`, `EpisodeUnderstandingTools` and the queue content.

Removed from the player body:
- the "Save the moment now…" helper (it becomes the Distill button's first-use tooltip);
- the Go deeper card;
- the inline effects;
- the Sleep timer outlined button.

Speed sheet:
- the current value as a large number;
- a slider from 0.5 to 3.0 in 0.05 steps, with haptic ticks;
- a presets row (1×, 1.2×, 1.3×, 1.5×, 2×);
- `ListItem` switches for "Use this speed for <show>", Skip silence and Volume boost;
- the boost-unavailable message as supporting text.

Sleep sheet: a chip grid of `SLEEP_TIMER_MINUTES` plus "End of episode". When a timer is active, the sheet shows the countdown, +N min and Cancel.

Video episodes keep `VideocastPlayer` in place of the artwork. Its capture and voice overlays stay as they are.

### 5.2 Episode (phase 2)

1. **Hero:**
   - artwork-tint gradient;
   - 96 dp artwork;
   - a show link (accent label with ›) and the date · duration;
   - the title at `titleLarge`.
2. **One filled button:** "Play", or "Resume from 12:30", full width.
3. **Icon action row:** Download (with progress ring and pause), Play next, Add to queue, Played, Share.
4. **Grouped list:**
   - Transcript, with state as supporting text from the existing `TranscriptTools` phase text, shortened. Tapping opens the transcript or starts preparation.
   - Summary & chapters, with an ⓘ for ChatGPT cost and privacy, which opens a sheet.
   - Ask this episode.
5. **About:** the description at 6 lines with "More".

"Transcribe locally again" and "Pause transcript" move to the transcript row's trailing menu.

### 5.3 Transcript (phase 2)

- **Group segments into paragraphs.** Start a new paragraph when the gap is over 1.2 s, the speaker changes, or the paragraph passes about 320 characters. Keep each segment's own timing for seeking and highlighting.
- **Layout:** a 38 dp muted timestamp gutter at labelSmall in tabular numerals, then serif reading text.
- **Current paragraph:** full-contrast text plus a 3 dp accent bar. Other paragraphs are text at 70%.
- **Saved moments:** an accent underline highlight. Tapping one opens the moment.
- **Text selection:** long-press, then "Distill selection", using the existing capture path, when the transcript source allows it.
- **Follow along:** auto-scroll during playback. A manual scroll pauses it and shows a "Follow along" pill.
- **Top bar:** search icon (expands a `SearchBar` with match count and ↑↓), plus an overflow holding preparation options and source info. Remove the repeated title heading.

### 5.4 Library (phase 3)

- **Continue listening:** a horizontal carousel of the 1 to 5 most recent in-progress episodes. Keep the single card when there is only one.
- **Your shows:** keep the rail.
- **Filters:** a sticky `SegmentedButton` header (`stickyHeader`).
- **Rows:** the compact #146 row stays, but in place of a card per row use:
  - flat rows with `outlineVariant` dividers;
  - a muted show name;
  - statuses in the meta line;
  - the saved-moment count as an accent "✦ N";
  - a 32 dp play button in the `s3` colour.
- **Feed problems:** keep the card. Retry joins pull-to-refresh.

### 5.5 Queue, Settings, Search, Saved, Show (phase 3)

**Queue**
- Sections: Now playing (an `accentContainer` row), then "Up next · N · total time".
- Reorder with a drag handle (`Modifier.draggable` or the reorderable lazy list pattern). Swipe left to remove, with an Undo snackbar.
- Keep the Move up / Move down / Remove TalkBack actions.
- "Clear" in the top bar opens a confirmation sheet.

**Settings**
- Grouped `ListItem`s with trailing current values, for example "Wi-Fi · 4.2 GB", "Base · auto", "Connected" in the success colour, "Synced 2 min ago".
- Remove "Make it yours".
- Sub-pages drop their repeated in-page title.
- Long explanations become one line plus a "Learn more" sheet. This covers the sync vs backup text, AI data use and automation.

**Search**
- Title "Search", matching the tab.
- An M3 `SearchBar` with IME search, and All / Audio / Video as a `SegmentedButton`.
- Recent searches as `ListItem`s.

**Saved**
- Remove the redundant "Saved" status chip.
- Quote clamped to 3 lines in `DistillType.quote`.
- One source line (art · episode · time · ▶).
- Filter chips by show.
- "Your posts" moves to the overflow menu.

**Show page**
- A "Following ✓" tonal button. Its menu holds Unsubscribe, with a confirmation dialog.
- Automation as a `ListItem` with a switch: "Prepare new episodes automatically". The cost detail moves behind ⓘ.

**Insights empty state**
- A preview card listing what you get (summary, chapters, ad markers, key quotes).
- The estimated scope ("≈ 40 min transcript").
- One filled "Generate insights" button.
- Privacy and cost shown as one muted line with ⓘ.

### 5.6 Navigation chrome

- **Top bar:** the settings gear appears only on Library. Elsewhere it moves into overflow menus.
- **Bottom navigation:**
  - indicator in `accentContainer`;
  - hidden on full-screen readers (Transcript, Insights, Ask), which get a back chevron instead;
  - the mini-player stays.
- **Mini-player:**
  - `s3`, `shape.md`, no border;
  - progress as a 2 dp accent line on its top edge;
  - artwork 40 dp, title, remaining time and play/pause;
  - the queue shortcut keeps its badge.

## 6. Icon

**Recommendation: concept A, the Ember Drop.**
- Keep the current Listening Drop geometry and the even-odd play opening.
- Fill it with a linear gradient: `#FFD58A` → `#FFB547` → `#E8830B`, at about 160°.
- Add an optional 55%-opacity specular ellipse.
- Put it on a radial ink plate (`#2A1E10` → `#0F0E0D`).
- The monochrome (themed) layer stays unchanged.

Alternatives, also on the report page:
- **B, Distilled Waveform:** bars cut into the drop.
- **C, Moment Drop:** a ✦ spark cut-out that matches the Distill button.

Production follows the existing `design/icon-proposal` pipeline:
- 108 dp adaptive layers with a 66 dp safe circle;
- a 512 px Play Store PNG;
- monochrome for API 33+.

Gradients in adaptive-icon vectors need `<aapt:attr>` gradient fills (API 24+). That's fine at minSdk.

Widget and notification:
- The widget backgrounds (`widget_*` drawables) and the media-session small icon use the same tokens. The widget accent is `accent` and its surfaces are `s2`/`s3`.

## 7. Implementation plan

Each phase is one PR.

Every PR includes:
- Compose screenshot or device captures at 100% and 160% font;
- a TalkBack pass on the changed screen;
- updated device tests where test tags or semantics move.

Test tags and accessibility labels that describe the same action are kept.

| Phase | Scope | Files (main) | Estimate |
|---|---|---|---|
| 0 | Token package; full `ColorScheme` (all roles); Typography (fonts per decision); Shapes; replace `Color(0xFF20243A)` and `ink` usages; spacing constants; a unit test that every role is explicit | new `ui/theme/*`, `ProductApp.kt`, `ListeningDesign.kt`, `DesignReviewUi.kt`, `res/font/*` | ≈1 day |
| 1 | Player: transport row, action dock, speed and sleep sheets, scrubber markers, peek sheet, artwork tint | `ProductApp.kt` (`PlayerPage`, `MiniPlayer`), `PlaybackEffectsControl.kt`, `SleepTimerControl.kt`, new `PlayerScrubber.kt`, `ArtworkTint.kt` | 3–4 days |
| 2 | Episode hero and action row; tools as ListItems; transcript paragraph reader, follow-along and highlights | `LibraryUi.kt` (`EpisodeDetail`), `TranscriptUi.kt`, `UnderstandingUi.kt` | ≈3 days |
| 3 | Grouped settings; queue drag and swipe; SearchBar; pull-to-refresh; sticky filters; Saved density; show page "Following"; Learn-more sheets; insights empty state | `DesignReviewUi.kt`, `ProductApp.kt` (`QueueContent`, `SettingsPage`), `SearchUi.kt`, `SavedMomentsUi.kt`, `ShowAutomationUi.kt`, `SubscriptionSyncUi.kt` | 3–4 days |
| 4 | Ember icon assets; light theme; Compose BOM upgrade (2025.02 → current) for M3 Expressive (`ButtonGroup`, wavy progress, motion scheme); widget restyle | `res/drawable/ic_launcher_*`, `res/mipmap-*`, `widget_*`, `app/build.gradle.kts` | 2–3 days |

Risk notes:
- **Phase 0 changes every screen's colours at once.** Verify against the device suites that read colours or screenshots. `ListeningDesignDeviceTest` is the known one.
- **The peek sheet and the transcript follow-along touch the playback position stream.** Keep the #141 rule: recompose on position ticks only in the leaf that shows time.
- **Queue drag must resolve by episode id**, as `moveQueue` does today. It must not use an index captured before the drag.
- **The BOM upgrade in phase 4 is a separate PR.** It can change test APIs.

## 8. Acceptance criteria

- [ ] No colour on any screen falls outside the token table. A test reflects over `ColorScheme`, and a manual sampling of screens finds no `#4A4458`, `#36343B`, `#2B2930` or `#211F26`.
- [ ] At most one filled `Button` per screen. This can be checked by a lint-style test over semantics trees in the device suites.
- [ ] The player fits on one 1080×2316 screen at 100% font without scrolling. Every control is reachable at 160%.
- [ ] All text and icons meet WCAG AA (4.5:1 text, 3:1 non-text) in dark mode, and in light mode when it ships.
- [ ] The TalkBack order on the player is: title → scrubber → transport → dock → sheet.
- [ ] No regression in the existing device suites (listening, queue, search, settings organisation, accessibility at 160%).
- [ ] Owner-device validation is navigation-only and preserves the account, queue, downloads and listening state, per CLAUDE.md.

## 9. Decisions

1. **Palette:** Ember.
2. **Fonts:** bundle Inter and Newsreader.
3. **Icon:** A, the Ember Drop, now.
4. **Light theme:** not decided. It stays out of scope until the owner asks; the token structure keeps it cheap.
