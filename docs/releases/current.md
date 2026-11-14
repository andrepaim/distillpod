DistillPod 0.29.0-alpha.1 tells you when a newer version is published, and YouTube episodes no longer look stuck when you press Play.

- **Update check.**
  - **Settings → About DistillPod** shows **Check for updates** (on by default), when it last checked and **Check now**.
  - When a newer release is published, About shows its version, a short summary, **Download APK** and **What's new**. The Settings overview shows **Update available**.
  - A daily check posts one quiet notification per new version. Tapping it opens About; its **Download APK** action opens the download link.
  - The app never downloads or installs anything itself. Your browser downloads the APK and Android installs it over the existing app, as before.
  - The daily check makes two requests to GitHub's public releases list and sends no account, library or device data. See [privacy](https://github.com/andrepaim/distillpod/blob/main/docs/privacy.md#network-requests).
- **YouTube playback starts visibly.**
  - Pressing Play on a YouTube episode first has to find its audio stream, which can take 15 seconds or more. Until now the player kept showing Play, 0:00 and "Duration unavailable" for that time, so it looked like nothing happened.
  - Now the Play button turns into Pause inside a spinning ring and the time reads **Loading…** until sound starts. Tapping it cancels the start. The mini-player and episode and queue rows also show Pause while loading.
  - Opening a YouTube episode's page now starts finding the stream in the background. Spend a few seconds on the page and Play starts in about 5 seconds instead of 15–20.

This personal alpha keeps the application ID and signing identity and raises the version code to **53**. The database schema, backups, sync, permissions and voice are unchanged; the update-check settings stay on the device. Install the signed APK over the existing app to keep your data; do not uninstall first. This is the first release with the update check, so you still need to install it by hand from GitHub Releases; later versions will be announced in the app.

**Known limits:**
- **Pressing Play right away:** if you press Play as soon as an episode page opens, the YouTube stream still takes its full time to find. The player now shows that it is loading.
- **Update check:** the daily background run and its notification have not been seen on a phone yet, because only release builds schedule it. This release is the first real run. **Download APK** was not tapped on a phone.

**Validation:**
- **CI:** both changes passed full CI on their pull requests, including on the combined code with each other (default and voice builds, lint, unit tests, Firebase rules, native bridge checks and isolated API 35 acceptance), followed by green Android CI on `main`.
- **Unit tests:** cover release selection, link checks, failures and oversized answers from GitHub, the About card at large text, notification content, the loading rule, the Play button's loading ring and which episodes start their stream early.
- **Phone:** on a Galaxy S22, separate debug builds found the real 0.28.0-alpha.1 release with **Check now** and opened **What's new**. Another showed the loading ring and **Loading…** after Play on a YouTube episode, and the faster start after time on the episode page. The signed update itself has not been installed on a phone.
- **Publication:** the release workflow separately validates and signs the tagged candidate.

[View changes since 0.28.0-alpha.1](https://github.com/andrepaim/distillpod/compare/v0.28.0-alpha.1...v0.29.0-alpha.1).
