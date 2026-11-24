DistillPod 0.36.0-alpha.1 stops diagnostics from writing to disk on the app's main thread, a cause of earlier "DistillPod isn't responding" stalls, and stops reporting ordinary feed and queue conditions as app failures.

- **Fewer stalls from diagnostics.** With cloud diagnostics on, playback and other screens logged each event on the calling thread and waited for its file to be forced to disk. Sentry recorded "isn't responding" reports (ANRs) from 0.28 to 0.31 whose main thread was waiting inside that logging ([#293](https://github.com/andrepaim/distillpod/issues/293)). Diagnostics now records the event in memory and writes it on its own background thread, in the order the events happened. The diagnostics settings screen also reads its reporting health in the background. With diagnostics off, nothing changes.
- **Feed errors are no longer reported as app failures.** A refresh that fails because the feed server returned an error, the feed could not be read, or there was no connection is already explained in Library. It is now logged as an expected condition rather than sent as an exception report. Other refresh errors are still reported ([#294](https://github.com/andrepaim/distillpod/issues/294)).
- **Less repeated queue logging.** A transcript waiting in the queue is logged when it joins the queue or its wait reason changes, not on every rescheduling pass. One session of 0.35 logged the same wait 38 times. A restart can log each waiting transcript once more.

Install the signed APK over the existing app; do not uninstall first. This personal alpha keeps application ID `dev.distillpod.android` and the existing signing identity, and raises the version code to **60**. Database schema, backup file format, diagnostics journal format and Firebase rules/protocol are unchanged from 0.35.0-alpha.1. Downgrading the APK is not supported.

**Known limits:**

- **Diagnostics writes can now be lost on a crash.** An operation step used to be on disk before logging returned. It is now written moments later, so an abrupt exit in between can drop it, as already happened with the detailed logs. This affects only diagnostics, not your library.
- **Not confirmed on a phone.** The ANRs were recorded months apart and on older versions, so the fix is verified by tests, not by watching for stalls. Sentry will show whether they stop on this version.
- **Background downloads still pause when Android blocks them.** The fix for downloads that Android stops from starting in the background ([#292](https://github.com/andrepaim/distillpod/issues/292)) is waiting for a phone test and is not in this release.
- **Still open from earlier releases:** the limits listed in the [0.35.0-alpha.1](https://github.com/andrepaim/distillpod/releases/tag/v0.35.0-alpha.1) notes and earlier, including the Android Auto scrolling change not yet tried in a car.

**Validation:**

- **Hosted CI:** [#295](https://github.com/andrepaim/distillpod/pull/295) and [#297](https://github.com/andrepaim/distillpod/pull/297) each passed full CI: builds, unit and Robolectric tests, lint, Firebase rules, native checks and isolated emulator acceptance. New tests check that diagnostics calls do no file I/O on the caller's thread and keep call order, how feed errors are classified, and that queue waits are logged once. CI now runs its voice build, release build and emulator acceptance in parallel ([#289](https://github.com/andrepaim/distillpod/pull/289)); this changes no app behavior.
- **Devices:** no emulator or phone checks were run for this release.
- **Publication:** the release workflow separately reruns full validation, release lint/assembly and signing. The downloaded assets are then checked for checksums, source identity, package/version, non-debuggable state, retained certificate and alignment.

[View changes since 0.35.0-alpha.1](https://github.com/andrepaim/distillpod/compare/v0.35.0-alpha.1...v0.36.0-alpha.1).
