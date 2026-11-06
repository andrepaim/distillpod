# Headphone settings consolidation

Validated November 6, 2026. UI revision `4976b18`; integrated preview revision `4449068`, including the isolated native-alignment fix `0405540`. Branch: `feat/headphone-settings`.

## Behavior

Playback & headphones is the only place that configures Previous-track capture and Next-track voice. Both cards identify the headphone command and its action. Next exposes setup, permission and readiness feedback; gesture/single-earbud/locked-phone instructions expand under Headphone setup. Feedback explains that the moment-saved sound also applies to phone and car captures.

Voice settings contains key management, answer preferences, a link to headphone controls, and an Android Auto setup sheet. Preference keys/defaults, credential storage and playback/voice behavior are retained.

## Checks

- 412 existing JVM/Robolectric tests passed: 91 core and 321 app, with no failures/errors/skips. No new tests were added for the layout change.
- Debug lint and enabled/default-disabled APK assembly passed. The integrated alignment build also passed debug assembly and lint.
- Disposable API 36 emulator: inspected normal and 1.3 font scales; both cards remained readable, help expanded/scrolled, and default-disabled builds showed only the available capture shortcut.
- Emulator setup flow: missing-key action opened Voice; saving an unissued fixture key and returning enabled configuration in the canonical page. Microphone denial displayed a setup/permission action. Granting permission through Android settings cleared the stale error on return. Readiness displayed after enabling. Both choices survived app restart. No recording or provider request was started.
- Physical S25 Ultra: installed the signed private preview `0.15.0-headphone-settings-preview.2`, version code 32, as a replacement. Signing identity and retained Firebase configuration matched the existing installation. Application identity and original installation date were retained. Inspected the combined controls page and navigation from Voice back to it; saved-key status and existing shortcut choices were retained. No owner preferences were changed during inspection. Left the app on Playback & headphones.

## Native alignment follow-up

The phone exposed an existing debug compatibility warning, also present in the preceding preview's Whisper ELF. The fix is isolated on `fix/android-16kb-alignment`: the Android target now uses the [NDK r27 linker flags recommended by Android](https://developer.android.com/guide/practices/page-sizes#compile) for maximum/common page sizes of 16384. No native algorithm or dependency was changed.

All six packaged arm64/x86_64 libraries passed LOAD-segment alignment and RELRO writable-page-overlap inspection; APK 16 KB ZIP alignment and the retained signature were verified. The updated preview opened on the S25 without the warning. That device reports a 4096-byte kernel page size, so this is binary validation and ordinary-device startup evidence, not execution under a 16 KB kernel or a new inference benchmark.

Private screenshots, APKs and the receipt are outside Git under the path recorded in `/tmp/distillpod-headphone-settings-dir`. Existing voice/headset/car acceptance limits still apply. No paid voice request, publication, remote CI run or backend deployment was performed.
