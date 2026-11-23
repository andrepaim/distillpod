DistillPod 0.35.0-alpha.1 puts your pinned and audio shows first in **Your shows**, and stops Android Auto lists from reloading while you scroll them when nothing in them has changed.

- **Your shows follows your pins.** The **Your shows** row in Library now puts shows pinned in the show gallery first. Within pinned and other shows, shows the gallery's **Audio** filter lists come before video-only ones. That includes mixed audio and video shows. Next comes the show with the newest episode, and shows with no dated episode go last. Pinning or unpinning a show reorders the row when you return. A refresh moves a show only when it brings a newer episode. If the row is still at its start, the new first show comes into view.
- **Smoother Android Auto lists.** The car reloads a whole list each time DistillPod reports a change, even while you are scrolling it. Before, DistillPod reported every open list on every library change, up to once per show during **Refresh all**, and again for each cover as it downloaded. Now a list is reloaded only when one of its rows visibly changes (title, show, artwork or order), at most once a second. Refresh bookkeeping and playback progress no longer reload lists. A cover that fails to download is retried after five minutes, not on every list load. This addresses the likely causes of the stutter, garbled rows and stalled scrolling reported in [#277](https://github.com/andrepaim/distillpod/issues/277), but it has not been confirmed in a car yet.

Install the signed APK over the existing app; do not uninstall first. This personal alpha keeps application ID `dev.distillpod.android` and the existing signing identity, and raises the version code to **59**. Database schema, backup file format and Firebase rules/protocol are unchanged from 0.34.0-alpha.1. Downgrading the APK is not supported.

**Known limits:**

- **Android Auto change not tried in a car.** It has not been checked on the Desktop Head Unit or a real head unit. [#277](https://github.com/andrepaim/distillpod/issues/277) stays open until scrolling a long Podcasts list is checked in a car, including during **Refresh all** and with covers still loading.
- **Your shows not tried on a device.** The new order is covered by unit and Robolectric UI tests only. If you have scrolled the row when its order changes, it keeps its scroll position rather than the show you were looking at.
- **Still open from earlier releases:** the limits listed in the [0.34.0-alpha.1](https://github.com/andrepaim/distillpod/releases/tag/v0.34.0-alpha.1) notes and earlier.

**Validation:**

- **Hosted CI:** [#278](https://github.com/andrepaim/distillpod/pull/278) and [#279](https://github.com/andrepaim/distillpod/pull/279) each passed full CI: builds, unit and Robolectric tests, lint, Firebase rules, native checks and isolated emulator acceptance. New tests cover each ordering rule, pin and refresh changes, and which Android Auto lists are reloaded. The Android Auto tests fail against the previous behavior, as does the test that brings a newly first show into view.
- **Devices:** no emulator, Desktop Head Unit or phone checks were run for this release.
- **Publication:** the release workflow separately reruns full validation, release lint/assembly and signing. The downloaded assets are then checked for checksums, source identity, package/version, non-debuggable state, retained certificate and alignment.

[View changes since 0.34.0-alpha.1](https://github.com/andrepaim/distillpod/compare/v0.34.0-alpha.1...v0.35.0-alpha.1).
