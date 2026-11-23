DistillPod 0.34.0-alpha.1 adds a first-run guide for new installations and fixes YouTube lectures that could stop at 0:00 with "Video is unavailable".

- **Welcome on a fresh install.** A new installation opens on Welcome. It shows an example of a saved passage and the takeaway ChatGPT can generate from it. From there you can **Connect ChatGPT**, choose **Find my first podcast** to go straight to Search, or **Bring back my library** when moving from another phone. **Skip setup** opens Library. Playback, on-device transcripts and Google library sync each work without ChatGPT.
- **Bring back my library.** The recovery step links to **Sync & backups** and **Import OPML**, so you can restore before adding shows or listening on the new phone. Before Android's file picker opens for **Connect existing backup**, DistillPod now explains that connecting the file turns on daily automatic backups, and that they stay on if you cancel the restore.
- **Setup guide in Library.** Under **Latest**, a card tracks following a show, setting up takeaways (the selected transcription model plus ChatGPT) and saving your first moment, with links to the matching Settings pages. Dismiss it at any time and reopen it from **Settings → About DistillPod → Setup guide**.
- **Browse before following.** A search result or pasted feed link now offers **Browse episodes**, which opens the show so you can play an episode without adding it to your library. **Follow podcast** is still there.
- **Fixed: some YouTube videos would not play.** For long videos with captions in many languages (such as Stanford lectures), YouTube's details could exceed the app's size limit. Audio then stayed at 0:00, Video reported "Video is unavailable" and the player showed "Playback stopped". DistillPod now asks YouTube only for the details it uses, which shrinks that lecture's response from about 10 MB to about 0.4 MB.
- **Fixed:** retrying the same episode clears the old "Playback stopped" message while it loads; a real failure still shows it. "Video is unavailable" now appears only when you chose Video, not when returning to Audio.

Install the signed APK over the existing app; do not uninstall first. This personal alpha keeps application ID `dev.distillpod.android` and the existing signing identity, and raises the version code to **58**. **Existing installations update straight to Library**, without Welcome or the setup card; the guide is available from **About**. Database schema, backup file format and Firebase rules/protocol are unchanged from 0.33.0-alpha.1. Downgrading the APK is not supported.

**Known limits:**

- **Live sign-ins from the guide not tried.** ChatGPT and Google sign-in were exercised with test fixtures, not live accounts, from Welcome and recovery. They reuse the existing Settings flows.
- **Switching to Video can be slow after a cold start.** On a Galaxy S22, the first Video request after the app was closed showed Loading for about 35 seconds while audio kept playing.
- **Still open from earlier releases:** the limits listed in the [0.33.0-alpha.1](https://github.com/andrepaim/distillpod/releases/tag/v0.33.0-alpha.1) notes and earlier.

**Validation:**

- **Hosted CI:** [#274](https://github.com/andrepaim/distillpod/pull/274) and [#273](https://github.com/andrepaim/distillpod/pull/273) each passed full CI: builds, lint, Firebase rules, native checks and isolated API 35 acceptance, including the new onboarding device tests.
- **Onboarding emulators:** on Android 15, seven fresh-install journeys passed, as did an in-place update over a seeded library (progress, backup file access and the scheduled backup kept) and an empty update from the 0.33.0-alpha.1 APK. Welcome stayed usable at 160% and 200% text, and recovery survived a background process kill. Android 10 passed a clean install and an in-place update.
- **Galaxy S22, onboarding:** an in-place private build kept its original install time and opened Library with no Welcome or setup card.
- **Galaxy S22, YouTube** (side-by-side debug build of #273): the Stanford CS329Z lecture 9 played audio from 0:00. Video showed its first frame and kept playing. Returning to Audio showed no error. A retry after a cold start also reached video.
- **Publication:** the release workflow separately reruns full validation, release lint/assembly and signing. The downloaded assets are then checked for checksums, source identity, package/version, non-debuggable state, retained certificate and alignment.

[View changes since 0.33.0-alpha.1](https://github.com/andrepaim/distillpod/compare/v0.33.0-alpha.1...v0.34.0-alpha.1).
