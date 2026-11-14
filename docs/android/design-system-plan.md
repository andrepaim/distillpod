# Ember design system: implementation plan

Owner decisions, made 14 Nov 2026:
- the **Ember** palette;
- **bundled** Inter and Newsreader fonts;
- the **Ember Drop** launcher icon, now.

The design rationale, tokens and screen specifications are in the [design system proposal](design-system-proposal.md). This plan turns them into reviewable pull requests.

## Ground rules

- **One PR per row of the table below.** Each PR references the tracking issue with `Refs #N`. No PR description says "close", "fix" or "resolve" for an issue it doesn't finish.
- **Behaviour is preserved.** These PRs change presentation only:
  - the database, playback service, sync, jobs and AI requests are untouched;
  - ViewModel actions are reused, not rewritten;
  - test tags and accessibility labels that describe the same action are kept;
  - moved controls keep their semantics, and TalkBack custom actions stay.
- **Merge discipline.** Merge only after green exact-head CI that includes current `main`. Verify CI on `main` after each merge.
- **Device work** follows CLAUDE.md:
  - synthetic fixtures run only on an isolated emulator;
  - the owner's S22 is used for navigation-only checks, and its account, queue, downloads and listening state are preserved;
  - owner-device screenshots stay out of Git.
- **Local gate for every PR:** `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`, plus the device suites that cover the touched screens.
- **Visual evidence for every PR:**
  - before and after emulator screenshots at 100% and 160% font, attached to the PR, not committed;
  - a TalkBack pass on each changed screen.

## Sequence

| # | PR | Depends on | Scope | Main files |
|---|---|---|---|---|
| 0a | **Theme foundation** | — | Token package; every M3 colour role set; Inter and Newsreader bundled; Typography, Shapes and spacing; hard-coded colours removed; semantic success colour; section headings downsized; show names muted; the role-coverage unit test. | new `ui/theme/*`, `ProductApp.kt`, `ListeningDesign.kt`, `LibraryUi.kt`, status call sites, `res/font/*`, `LicenseAndSources.kt` |
| 0b | **Ember Drop icon** | — (parallel with 0a) | Adaptive launcher foreground (gradient + highlight), radial background, monochrome kept, branding SVG/PNG, widget accents. | `res/drawable/ic_launcher_*`, `branding/*`, `widget_*`, `security/reviewed-assets.json` |
| 1 | **Player** | 0a | Transport row, action dock, speed sheet, sleep sheet, scrubber chapter gaps and moment dots, peek sheet, artwork tint, mini-player restyle. | `ProductApp.kt` (`PlayerPage`, `MiniPlayer`), `PlaybackEffectsControl.kt`, `SleepTimerControl.kt`, new `PlayerScrubber.kt`, `ArtworkTint.kt` |
| 2 | **Episode and transcript** | 0a | Episode hero, one primary button, icon action row, tools as `ListItem`s; transcript paragraphs, serif reading, follow-along, moment highlights. | `LibraryUi.kt` (`EpisodeDetail`), `TranscriptUi.kt`, `UnderstandingUi.kt` |
| 3a | **Lists and chrome** | 0a | Grouped settings `ListItem`s with trailing values; "Learn more" sheets replacing long paragraphs; the show page's "Following" button; the insights empty state. | `DesignReviewUi.kt`, `ProductApp.kt` (`SettingsPage`), `SubscriptionSyncUi.kt`, `ShowAutomationUi.kt`, `UnderstandingUi.kt` |
| 3b | **Library, queue, search, saved** | 0a, ideally after 1 | Flat episode rows; sticky segmented filters; continue-listening carousel; pull-to-refresh; queue drag and swipe with Undo; `SearchBar`; Saved density and filters. | `ProductApp.kt` (`LibraryPage`, `QueueContent`), `LibraryUi.kt`, `SearchUi.kt`, `SavedMomentsUi.kt` |
| 4 | **Compose BOM upgrade** | 1–3 merged | `compose-bom` 2025.02 → current stable; adopt M3 Expressive where it replaces custom code (button groups, wavy progress, motion scheme). | `app/build.gradle.kts`, affected call sites |

### Parallelism

- **0a and 0b run in parallel.** Their files don't overlap.
- **After 0a merges, 1, 2 and 3a can run in parallel.**
  - 1 and 3a both touch `ProductApp.kt`, but in different functions (`PlayerPage`/`MiniPlayer` vs `SettingsPage`). Conflicts are expected to be mechanical.
- **3b follows 1,** because both restyle the mini-player and bottom chrome in `ProductApp.kt`.
- **4 is last.** A BOM upgrade can change test APIs and should be isolated.

## 0a. Theme foundation, in detail

1. **`ui/theme/DistillColors.kt`.**
   - `EmberDark`, a `ColorScheme` with **every** role set from the proposal's token table.
   - `DistillExtras` (`success`, `warning`, `artworkFallback`), provided through `LocalDistillExtras` and read with `MaterialTheme.extras`.
2. **`ui/theme/DistillType.kt`.**
   - `FontFamily` objects for Inter (400/500/600/700) and Newsreader (400, 400 italic, 500).
   - The M3 `Typography` from the proposal's type scale.
   - `DistillType.quote` and `DistillType.reading`.
3. **`ui/theme/DistillShapes.kt` and `DistillSpacing.kt`.**
   - Shapes: xs 8, sm 12, md 16, lg 24, full.
   - Spacing on a 4 dp grid.
4. **`ui/theme/DistillTheme.kt`.** `DistillTheme { }` replaces the private scheme in `ProductApp.kt`.
5. **Fonts.**
   - Download Inter and Newsreader from the `google/fonts` repository.
   - Instance static weights and subset them to Latin, Latin-1 Supplement, Latin Extended-A and the punctuation in use (Portuguese is covered).
   - Store them as `res/font/*.ttf`, about 480 KB in total.
   - Ship each `OFL.txt` in `assets/licenses/` and list both fonts in `THIRD_PARTY_NOTICES.md`.
6. **Call-site changes.** These are style substitutions only; no layout changes.
   - `Scaffold`, `TopAppBar` and `NavigationBar` use theme roles instead of `ink`.
   - The resume card drops `Color(0xFF20243A)` for `surfaceContainerHigh`.
   - `SectionHeading` changes from `titleLarge` to `titleSmall` (15 sp / 700).
   - Show names in lists move from `secondary` to `onSurfaceVariant`.
   - Positive states move to `extras.success`: Connected, Ready, latest backup, sync status, Following.
   - Highlighted row statuses ("Offline", "N saved") use `primary`, the Ember accent.
7. **Tests.**
   - A JVM unit test asserts that no `EmberDark` role equals the corresponding M3 baseline default.
   - A second JVM test checks WCAG contrast for the key pairs: text, muted, accent and outline against their surfaces.
   - The existing device suites run unchanged.

**Acceptance:** none of the leaked baseline colours (`#4A4458`, `#36343B`, `#2B2930`, `#211F26`) appear on any screen. Text and icons pass AA.

## 0b. Ember Drop icon, in detail

- **Foreground** (`ic_launcher_foreground.xml`):
  - the existing compound path and even-odd play opening;
  - fill: an `aapt:attr` linear gradient `#FFD58A` → `#FFB547` → `#E8830B`;
  - a specular ellipse `#FFF3DC` at 55% alpha.
- **Background** (`ic_launcher_background.xml`): a radial gradient `#2A1E10` → `#0F0E0D`.
- **Monochrome:** unchanged.
- **Branding:** `branding/icon.svg` updated to match, and `branding/icon-512.png` re-exported, with its checksum updated in `security/reviewed-assets.json`.
- **Widgets:** the distill accent drawables use Ember `#FFB547` on `#22201D`/`#2C2925` surfaces.
- **Validation:**
  - the foreground stays inside the 66 dp safe circle;
  - inspect it on the emulator launcher (normal and themed icons);
  - on the S22, install only through the existing upgrade path, and only when the owner requests an install.

## Phases 1 to 3b

Each PR implements the matching section of the proposal (5.1–5.6). Additional constraints:

- **Player (1):**
  - Position ticks recompose only the scrubber and time labels. The #141 performance guard stays green.
  - Distill keeps `canCaptureMoment`.
  - The video path (`VideocastPlayer`) keeps its overlays.
  - The peek sheet reuses `TranscriptTools`, `EpisodeUnderstandingTools` and the queue content.
- **Transcript (2):**
  - Paragraph grouping is a pure function with unit tests: gap > 1.2 s, speaker change, or ~320 characters.
  - Seeking and the #179 focus use the original segment times.
- **Queue (3b):**
  - Drag resolves by episode id through `moveQueue`.
  - Swipe-remove uses `removeQueue` with an Undo snackbar that re-inserts at the same index.
  - TalkBack custom actions are unchanged.
- **Settings (3a):** trailing values read existing preference flows. Nothing is computed on the main thread.

## 4. Compose BOM upgrade, outcome

- **Upgrade:** `compose-bom` 2025.02.00 → **2026.06.01** (Compose 1.7.8 → 1.11.4, Material 3 1.3.1 → 1.4.0).
  - Kotlin 2.1.10, Android Gradle Plugin 8.9.1 and compileSdk 35 are unchanged.
  - BOM 2026.08.00 and later (Compose 1.12) require Android Gradle Plugin 9.1 and compileSdk 37. That is a separate toolchain migration, and those BOMs still ship Material 3 1.4.0.
- **Pinned defaults:** Material 3 1.4 changed component defaults that the Ember review had approved. `ui/theme/DistillComponents.kt` restores them with same-named `Button`, `OutlinedButton`, `TextButton` and `FilterChip` wrappers, plus the navigation item colours:
  - Outlined buttons: amber label and `outline` border, instead of a grey label and `outlineVariant` border.
  - Unselected filter chips: `outline` border.
  - Disabled button labels: `onSurface` at 38%.
  - Selected navigation label: `onSurface`, not `secondary`.
  - `EmberComponentDefaultsTest` fails when a UI file calls one of these components without importing the Ember wrapper.
- **Visual check:** `VisualBaselineDeviceTest` compared 22 screens and sheets before and after the upgrade on the same emulator.
  - 19 are pixel-identical.
  - Saved and Your shows shift by 1 px below their search fields.
  - The speed slider's inner track corners are slightly squarer.
  - Not covered by the comparison: a filled icon button's disabled container is 10% instead of 12%.
- **Not adopted: M3 Expressive.** Material 3 1.4.0, the latest stable, keeps button groups, wavy progress, loading indicators and the expressive motion scheme internal. They become public in Material 3 1.5.0, which is still a release candidate (1.5.0-rc01) and requires compileSdk 37 and Android Gradle Plugin 9.1. Adoption is tracked separately, after 1.5.0 is stable and the toolchain migration lands.

## Tracking

- One GitHub issue tracks the programme, with a checklist of the PRs above.
- Each PR links the issue and the relevant proposal section.
- The issue closes after PR 4 merges and a navigation-only S22 check of the installed build.
