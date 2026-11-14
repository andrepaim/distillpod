# DistillPod icon proposal — The listening drop

November 3, 2026. Originally a standalone design proposal on a local web server. The owner selected the recommended lavender Listening Drop, which is now the default launcher icon in **0.4.1-alpha**, installed on the S22 Ultra. This directory retains the design exploration and editable source assets.

> **Superseded, November 14, 2026:** the owner approved **Ember Drop** (concept A), which replaces the lavender default. It keeps this silhouette and the transparent even-odd play opening, recoloured with an amber gradient (`#FFD58A` → `#FFB547` → `#E8830B`), a soft specular highlight and a warm radial background (`#2A1E10` → `#0F0E0D`). The installed resources live in `app/src/main/res/drawable/ic_launcher_*.xml`, with `branding/icon.svg` and `branding/icon-512.png` as the masters; the monochrome layer is unchanged. The lavender assets in this directory are kept as historical exploration and were not regenerated.

## Design decision

Recommend a solid lavender drop on ink, with a rounded play opening cut through the silhouette. The drop connects to “distill”; the opening identifies listening. Its asymmetric shoulder adds movement without adding detail. The shape works as a single-color mark, so the identity survives Android themed icons.

Use the existing native app palette: ink `#0C0F16`, lavender `#B7B9FF`, mint `#91DCC8`. Lavender is the recommended foreground; mint is an alternative. “Themed” is an illustrative wallpaper-derived palette, not a prediction of the colors Android will choose. The website's warm paper is presentation framing, not a proposed replacement for the app theme.

The signal D alternative is compact but suggests audio hardware. The audio bookmark expresses saved moments but makes the whole app feel like a notes tool. Keep the listening drop as the primary identity. The page shows the alternatives rather than presenting arbitrary variations as equally strong choices.

## Proposal experience

- Original vector concepts and the construction rationale.
- Live lavender, mint and themed previews, with three illustrative launcher masks.
- A clearly labeled illustrative Android home screen and widget.
- Actual-size samples from 16 to 96 CSS pixels; inspect at 100% browser zoom.
- A toggle showing the adaptive foreground's central 66 dp safe circle.
- Local downloads of the SVG master and an asset kit. The page has no account, analytics or external asset requests.

## Assets and geometry

`assets/distillpod-lavender.svg` is the full-square master for store/web previews, using the central 72 dp viewport of the 108 dp adaptive canvas. `distillpod-foreground.svg`, `distillpod-background.svg` and `distillpod-monochrome.svg` are separate 108 × 108 layers. The playback opening is genuine transparency, not a background-colored overlay. PNG exports contain full square backgrounds with no baked exterior shadow or rounded corners. The 512 px lavender candidate is under Google Play's 1024 KB limit.

The downloadable Android XML files retain the same compound path and even-odd fill as the master. The installed resources live in `app/src/main/res`, with a base adaptive icon in `mipmap-anydpi-v26` and an additional monochrome layer in `mipmap-anydpi-v33`. Both the regular and round manifest icon references use this selected mark.

Reference specifications:

- [Android adaptive icon guidance](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive): separate 108 dp layers, central 66 dp safe area, and a monochrome layer for launcher theming.
- [Google Play icon specifications](https://developer.android.com/distribute/google-play/resources/icon-design-specifications): separate 512 × 512 square PNG store asset, no baked outer masking or shadow.

Manrope is bundled locally under its [SIL Open Font License](assets/Manrope-OFL.txt). All icon paths were authored for this proposal; no third-party icon art is included.

## Run and rebuild

From the repository root:

```sh
python3 design/icon-proposal/build-assets.py
npm --prefix design/icon-proposal ci
npm --prefix design/icon-proposal exec playwright install chromium
node design/icon-proposal/build-previews.cjs
python3 design/icon-proposal/package-assets.py
python3 -m http.server 8136 --bind 127.0.0.1 --directory design/icon-proposal
```

Open `http://localhost:8136`. The server serves only this proposal directory. Browser export tooling uses the existing Playwright dependency in this proposal’s own `node_modules` after `npm ci`.

## Checks and next implementation step

Check desktop and mobile layouts, color/shape controls, keyboard access, downloads, local-only asset loading, foreground safe-area containment, and the transparent play opening. Browser masks are approximations; physical-device launcher masking, parallax and themed rendering remain implementation checks.

Proposal validation: all nine color/shape combinations update the main preview and launcher consistently. Chromium reports no horizontal overflow at 320, 360, 390, 680, 768, 1024 or 1440 px, no page errors or failed asset requests, and no external page requests. Desktop and mobile screenshots were inspected. The safe-area toggle, ZIP download, PNG delivery, XML parsing and ZIP integrity pass. The foreground's maximum raster-sampled radius is 30.612 dp against a 33 dp safe circle; the play opening has zero alpha. Windows localhost access returned HTTP 200, and the proposal was opened in the desktop browser.

Android delivery: build and lint pass. The installed APK hash matches the built artifact and retains the original signing certificate. The new icon was visually inspected on the S22 home screen and successfully launched the app. Subscription identities, all library table counts, queue, player session, listening/download state, encrypted account vault and settings checksums match their pre-upgrade snapshots. The phone was left on its home screen with the new icon visible. Physical themed-mode and launcher-motion checks remain separate; the monochrome resource is packaged. See [the installed build receipt](../../docs/android/evidence/launcher-icon-build.json).
