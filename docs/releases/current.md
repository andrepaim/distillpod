DistillPod 0.31.0-alpha.1 fixes distills made from the car ending as **Needs retry**.

- **Car distills finish on their own.** Pressing Distill in Android Auto saves the moment immediately, as before. Its AI insight then needs network access for 10–20 seconds. In a car the connection drops often: Wi-Fi giving way to mobile data as you drive off, tunnels, or wireless Android Auto using the phone's Wi-Fi. Until now, a drop during that window ended the moment as **Needs retry**. Now the moment waits, and generation continues when the network returns, reusing any result already received. This also covers distills from headphones, the widget and the phone itself.
- **Pause and real errors are unchanged.** Pausing generation still keeps the moment paused. Quota, sign-in and other genuine failures still show their usual messages.

Install the signed APK over the existing app; do not uninstall first. This personal alpha keeps application ID `dev.distillpod.android` and the existing signing identity, and raises the version code to **55**. Database schema, backup format and Firebase rules/protocol are unchanged from 0.30.0-alpha.1. Updating from 0.29.0-alpha.1 or earlier also brings everything in [0.30.0-alpha.1](https://github.com/andrepaim/distillpod/releases/tag/v0.30.0-alpha.1), including its additive database upgrade. Downgrading the APK is not supported.

**Known limits:**

- **Moments already marked Needs retry** are not repaired automatically. Tap Retry insight on each one.
- **An AI request cut off by the drop** is sent again once the network returns, which uses a little more of your ChatGPT allowance. A result that had already arrived is reused.
- **A request that fails while the job keeps running,** for example a server error, still ends as a failure. Retry it from the moment.
- **Your own car hasn't been tested.** The car scenario was reproduced and verified with Android's Desktop Head Unit on a Galaxy S22. Confirming it in your car is still worthwhile.
- **Still open from 0.30.0-alpha.1:** the limits listed in [its release notes](https://github.com/andrepaim/distillpod/releases/tag/v0.30.0-alpha.1), including [#251](https://github.com/andrepaim/distillpod/issues/251).

**Validation:**

- **Hosted CI:** the fix ([#262](https://github.com/andrepaim/distillpod/pull/262), issue [#261](https://github.com/andrepaim/distillpod/issues/261)) passed full CI, including default/voice builds, lint, Firebase rules, native checks and isolated API 35 acceptance. Android CI on `main` then passed after the merge.
- **New tests:** cover a stop during generation, a request cut off by the stop, a stop after the candidate arrived, Pause staying paused, and a genuine error still failing.
- **Galaxy S22 with Desktop Head Unit:** phone locked, side-by-side debug build. Distill was pressed in the car and Wi-Fi turned off for 8 seconds during generation:
  - `main` before the fix ended as **Needs retry**;
  - the fix resumed when the network returned and finished.
  This is feature evidence from a debug build, not an installation of this signed release.
- **Production evidence:** Sentry recorded the same failure on 0.23 and 0.28 car distills (`cancelled` 10–18 seconds after generation started).
- **Publication:** the release workflow separately reruns full validation, release lint/assembly and signing. The downloaded assets are then checked for checksums, source identity, package/version, non-debuggable state, retained certificate and alignment.

[View changes since 0.30.0-alpha.1](https://github.com/andrepaim/distillpod/compare/v0.30.0-alpha.1...v0.31.0-alpha.1).
