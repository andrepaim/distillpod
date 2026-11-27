# Update check

DistillPod is installed from [GitHub Releases](https://github.com/andrepaim/distillpod/releases), so nothing told users when a newer APK was published. The app now checks once a day and offers the download link. It does not download or install anything itself: the browser downloads the APK and Android's installer updates the app as before.

Status: implemented November 14, 2026 (step 1). Step 2, an in-app download verified against `SHA256SUMS` and the signing certificate and handed to Android's installer, is not planned yet.

## Behaviour

- **Settings → About DistillPod** shows **Check for updates** (on by default), the time of the last check and **Check now**.
- When a newer release exists, About shows a card with its version, the lead paragraph of its release notes, **Download APK** (the release's APK link) and **What's new** (the release page). The card reminds you to install over the current app without uninstalling.
- The Settings overview shows **Update available** on the About row.
- A daily background check posts one notification per new version, on a silent "App updates" channel. Tapping it opens About; its **Download APK** action opens the APK link. If notifications are off, the update still appears in About.
- A manual check reports "You have the latest version." or "Couldn't reach GitHub…", and counts as having seen that version, so the daily check does not announce it again.
- Turning the switch off cancels the daily check. A manual check still works.

## How a release is chosen

1. `GET https://api.github.com/repos/andrepaim/distillpod/releases?per_page=10`, unauthenticated, at most 1 MB. Prereleases count, because every DistillPod release is a prerelease; drafts never do.
2. Releases are taken in GitHub's order, newest created first. The first five that have a `v<version>` tag, a `distillpod-<version>.apk` asset and a `build-info.json` asset are candidates. A release still uploading its assets is skipped.
3. Each candidate's `build-info.json` (at most 16 KB) must name `dev.distillpod.android`, the same tag and version. The valid candidate with the highest `versionCode` wins, so a backport of an older line published later does not hide a newer release. That `versionCode` is compared with the installed app's. Version names are never compared: a private phone build can be ahead of the newest release, as code 51 was ahead of 0.27.0's code 50.
4. Only `https://github.com/andrepaim/distillpod/releases/...` links are stored or opened.

A failed or oversized request is an error, never "up to date". The background job then waits for its next daily run instead of retrying sooner. GitHub allows 60 unauthenticated requests per hour per address; a check makes at most six (the list and up to five build records).

## Builds and tests

Only GitHub builds contain the update check. It is a build-time
[distribution switch](../development.md#distribution-switches), `distillpodUpdateCheckEnabled`, on by
default and off in the Play preset (`-PdistillpodDistribution=play`), because a Google Play app may
only be updated by Play (#320). A build without it has no **Check for updates** setting, no update
card or **Update available** row, no notice and no scheduled job, and never requests
`api.github.com`: `ReleaseFeed`, `UpdateChecker`, the worker, the notice and the About section live in
`app/src/updateCheckOn`. At startup it cancels a daily check left by an earlier GitHub build, which
WorkManager can do without the worker's class. `UpdateCheckOffTest` covers this; the
[distribution artifact check](../development.md#distribution-switches) fails if the release list
address, the worker or the setting's title is in the Play APK.

Only non-debuggable builds schedule the daily check, so debug, emulator and CI builds never contact GitHub on their own. The manual check works in every build.

The result, the last check time, the switch and the last announced version code are stored in the device's settings. They are not part of backups or library sync.

Tests: `AppUpdatesTest` covers release selection against a local server, link validation, failures and oversized answers, summaries, and storage with once-per-version announcements. `AppUpdatesScreenTest` covers the About card at 1.6× text, the switch, the manual check and the overview row.

## Privacy

The check sends no account, library or device data. GitHub sees the network address, the time and a generic `DistillPod/0.2 (Android)` user agent. See [privacy](../privacy.md#network-requests).
