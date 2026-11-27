DistillPod 0.38.1-alpha.1 keeps a background transcript waiting instead of failing when Android blocks the app's network, and stops background transcription from using up a day's diagnostics allowance in under two hours.

- **A background transcript no longer fails because Android blocked the network.** A transcript left to run in the background (waiting for a charger, Wi-Fi or battery) for an episode whose audio was not downloaded could fail with "Could not reach the server", although the phone was online ([#312](https://github.com/andrepaim/distillpod/issues/312)). Android was blocking DistillPod's network in the background at that moment. The transcript now goes back to the queue, shows **Queued · waiting for network access**, and fetches the audio when Android allows the network again. Transcripts of downloaded episodes are not held back by one that is waiting.
- **Background transcription uses far fewer diagnostic lines.** This matters only when cloud diagnostics are on. Each two-minute transcript window used to write 19 routine lines, so the daily allowance of 800 ran out after about 96 minutes on a Galaxy S22 and that phone reported nothing more for the rest of the day ([#313](https://github.com/andrepaim/distillpod/issues/313)). A window now writes 2 or 3 lines, which leaves room for roughly nine hours of background transcription a day. Transcripts that are still waiting are no longer logged again each time the app starts. Failures, pauses and completions are reported as before, and the allowance itself is unchanged.

Install the signed APK over the existing app; do not uninstall first. This personal alpha keeps application ID `dev.distillpod.android` and the existing signing identity, and raises the version code to **63**. Database schema, backup file format, diagnostics journal format and Firebase rules/protocol are unchanged from 0.38.0-alpha.1. Downgrading the APK is not supported.

**Known limits:**

- **A waiting transcript can wait a long time.** While the phone keeps blocking DistillPod's network and the app stays alive in the background, the transcript does not continue. On the test phone that block did not lift in 16 minutes; the transcript continued after Android replaced the app's process. Opening DistillPod should release it too, but that was not checked on a phone.
- **A waiting transcript does not notice audio that arrived another way.** If the episode is downloaded while its transcript waits for the network, the transcript still waits for the network.
- **The episode keeps a queued download.** An episode whose transcript waited is downloaded after the next app start, even if you paused that transcript or it failed in the meantime.
- **The cause is partly inferred.** The field failure was reproduced on a phone with a network block forced for the test app. Android's own block and the in-app start of the transcript were each observed, but not producing the failure together.
- **The diagnostics fix needs field data.** The frequent app starts behind the old volume are inferred from upload timing. A day of reports from a phone on this version is needed to confirm that no routine lines are dropped.
- **Still open from earlier releases:** the limits listed in the [0.38.0-alpha.1](https://github.com/andrepaim/distillpod/releases/tag/v0.38.0-alpha.1) notes and earlier, including long background downloads that wait until DistillPod is opened ([#292](https://github.com/andrepaim/distillpod/issues/292)).

**Validation:**

- **Hosted CI:** [#314](https://github.com/andrepaim/distillpod/pull/314) and [#315](https://github.com/andrepaim/distillpod/pull/315) each passed full CI: builds, unit and Robolectric tests, lint, Firebase rules, native checks and isolated emulator acceptance. #315 passed on a head that already contained #314. One attempt of the unit-test job on #315 was cancelled after running 30 minutes without a result and passed when rerun; the cause was not found.
- **Phone (Galaxy S22, Android 16):** checked with separately installed test builds of the same code, not with this APK, and with a network block forced for the test app only.
  - The 0.38.0-alpha.1 code failed both not-downloaded transcripts with "Could not reach the server" within a second, as reported.
  - With the fix, transcripts started while the network was blocked stayed queued and none failed. A transcript of a downloaded episode kept running meanwhile.
  - After the block was lifted, the waiting transcript retried within the same second, downloaded its audio (about 20 MB) and started transcribing. It was still running nine minutes later; its first saved window was not awaited.
  - An earlier version of the fix failed at that retry with "The operation could not finish". The phone check found it, and it was fixed with a regression test before merging.
- **Diagnostics volume:** verified by unit tests that count the lines written for a transcript window and a process start. Not checked on a phone, because test builds do not upload diagnostics.
- **Publication:** the release workflow separately reruns full validation, release lint/assembly and signing. The downloaded assets are then checked for checksums, source identity, package/version, non-debuggable state, retained certificate and alignment.

[View changes since 0.38.0-alpha.1](https://github.com/andrepaim/distillpod/compare/v0.38.0-alpha.1...v0.38.1-alpha.1).
