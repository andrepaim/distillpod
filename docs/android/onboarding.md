# First-run setup

Proposed November 17, 2026. The first-run flow and Library card are design proposals. The unified Sync page and daily automatic backup already exist on main. [Sync design](https://github.com/andrepaim/distillpod/blob/fe0242e18224bcc069b7e848ebf5b6a356838aed/docs/android/sync-only-settings.md) · [Implementation PR #270](https://github.com/andrepaim/distillpod/pull/270) · [0.33.0-alpha.1 release preparation #271](https://github.com/andrepaim/distillpod/pull/271)

Screen mockups come before code and need the owner's approval; items marked **To verify** are assumptions that have not been tested.

## Problem

A fresh install opens on an empty Library that says "Your library starts here" with a **Find podcasts** button. Everything that makes DistillPod more than a podcast player needs setup that the app never mentions:

| Capability | Where it is set up today | What happens without it |
| --- | --- | --- |
| Takeaways, summaries, chapters, episode chat, explanations, suggestions | **Settings → ChatGPT** | A saved moment keeps its source but gets no takeaway |
| Transcripts for saved moments | **Settings → Transcript preparation**, download Tiny or Base | Transcript preparation waits for a model that was never downloaded |
| A library that follows you to another phone | **Settings → Sync → Connect Google & sync library** (configured builds) | Changes stay on this phone until the user connects |
| Recover library text and posts from a dated copy | **Settings → Sync → Set up automatic backup** on the original phone, then **Restore from a backup** on the new phone | Without a backup file there is no snapshot to restore |

These steps are described in [Getting started](../getting-started.md), which a new user is unlikely to read before opening the app. Order also matters on a second phone: following shows before connecting sync adds feeds to review for public sharing. An empty library still gets the sign-in confirmation.

## Goals

- A new user reaches a first saved moment with a takeaway without reading documentation.
- A returning user restores their library before adding anything to the new phone.
- Each connection is explained once, at the point of choosing it: what it does, what leaves the phone, and what works without it.
- Every step can be skipped, and anything skipped stays easy to find later.

## Non-goals

- No change to stored preferences, defaults, sync protocol, backup format or the Settings routes.
- No new place to edit a preference. Settings remains the only editor for each one.
- Not shown to existing installations, including the owner's phone.
- The notification request stays where #193 put it, after the first download or transcript job.
- Headphone capture, Voice, the widget and Android Auto are not setup steps. They are linked, not walked through.
- No account switching or account erasure; those remain unimplemented.

## Overview

**Welcome** appears once on a fresh install. **Find my first podcast** opens Search immediately. **Bring back my library** opens the recovery choices before the listener adds shows or changes playback progress. **Skip setup** opens Library.

The Library card takes over after either path. Once the listener starts an episode, it offers **Set up takeaways** and shows what the selected transcription model and ChatGPT each contribute.

## First-run state

One device-local value, `pending` or `done`, plus a `cardDismissed` flag. Both live in the device's settings store, outside `PortablePreferences`, so they are never part of a backup file or library sync. The manifest already excludes all app data from Android cloud backup and device transfer, so a reinstall always starts without them.

On the first launch that finds no stored value, the app decides once:

| Condition | Result |
| --- | --- |
| No followed shows, no episodes, no saved moments, ChatGPT not connected, no sync binding, no connected backup file, and the package was never updated (`firstInstallTime == lastUpdateTime`) | `pending` |
| Anything else | `done` and `cardDismissed`, written silently |

The second row is what protects existing installations: the release that introduces this feature arrives as an update, so its first launch records `done` and shows nothing. **To verify:** that `firstInstallTime == lastUpdateTime` holds on a fresh sideloaded install and differs after an update, on the emulator and on Android 10.

The decision is a pure function in `core/` taking those inputs, so the table is unit-tested without Android.

`pending` becomes `done` when the listener selects **Find my first podcast**, chooses **Open library** from recovery, or taps **Skip setup**. These actions remove the setup screens from the back stack. Opening Sync or a file picker keeps recovery pending. Only the state is stored, not the step. The back stack already lives in saved state, so a process death mid-flow returns to the same screen. If the task is removed instead, the flow starts again from Welcome, and each screen shows the live result of anything already connected.

**Instrumented tests.** About 50 device-test files launch `MainActivity` on an empty library, and the documentation capture script photographs that Library. `IsolatedTestRunner` therefore forces `done` and `cardDismissed` before the Application is created, the same way it already disables cloud sync. Tests of this feature opt back in.

## The flow

Welcome and Bring back my library both offer **Skip setup**. Back returns from recovery to Welcome and exits the app from Welcome. Hide the bottom navigation and mini-player on these two screens. Search, Settings and the player keep their usual navigation.

### Welcome

“Listen to a podcast and save the last minute as a moment. You can set up short takeaways while you listen.”

- **Find my first podcast** goes directly to Search. The listener can find an episode and press Play without following a show, signing in or downloading a model.
- **Bring back my library** opens recovery. The listener can choose **Open library** whenever they are ready.

### Bring back my library

Offer **Sync & backups** and **Import OPML**, followed by **Open library**. The first action uses the same destination as the Library link, **Settings → Sync** (`settings:sync`). Back from **Backups & restore** (`backup`) returns to Sync, and Back from Sync returns to this step. A listener bringing subscriptions from another podcast app can open the existing OPML picker directly.

| What the listener has | What to do on the Sync page | What returns |
| --- | --- | --- |
| A library linked to Google | **Connect Google & sync library**, review public feeds, then choose the original Google account | Approved subscriptions, progress, played status and saved moments. Queue and model/language choices depend on the account’s existing scope. |
| A managed backup file | **Restore from a backup → Connect existing backup**, choose the file, then **Restore a backup**, select a dated snapshot, review it and choose **Merge library** | Missing library records, including transcripts, finished analyses, chats and posts. Existing records and progress are kept. Language and model choices are opt-in. |
| An older standalone JSON export | **Restore from a backup → Import older backup**, then review and merge | The library data present in that export. |

When the listener has both Google sync and a backup, suggest signing in first and letting shared records arrive before merging the snapshot. A restored record that overlaps a pending cloud change may still need review in Sync. Someone with only a backup can restore immediately, including on builds without Firebase. Audio downloads, model files and account credentials need separate setup. Use the existing **What’s covered** explanation for build- and account-specific coverage, including eligible finished content when enabled. [Library sync and restore](subscription-sync.md#backup-restore)

Show the original-account binding disclosure before offering Google sign-in. Signing out pauses sync and retains the binding. Onboarding never requests an account-wide scope upgrade. In an unconfigured build, omit the Google guidance and keep the backup and OPML paths.

Place this disclosure next to **Connect existing backup**, where the listener can read it before opening Android’s picker: “Connecting this file lets DistillPod add daily backups to it. You’ll choose which snapshot to restore next. If you cancel the restore, automatic backup stays on.” **Turn off automatic backup** disconnects the file and keeps its history. Keep **Set up automatic backup** for someone creating a new file.

The daily job writes when the library has changed, the phone is online and the battery is sufficient. A listener can choose a private file on the device or with a provider such as Drive. Android grants access to the file separately from Google sync sign-in. The provider handles any cloud upload. [Automatic backup and restore](drive-backup.md)

## The setup card

The first item of the Library list on the main Library page (not on a show page, and only under the **Latest** filter). It is shown while the state is `done`, `cardDismissed` is false, and at least one core item is undone.

| Item | Done when | Next action |
| --- | --- | --- |
| Follow a show | Any show is followed | Open Search. Following is optional for playback. |
| Set up takeaways | The **selected** local transcription model is ready and ChatGPT is connected | Show each prerequisite and link directly to its Settings page. |
| Save your first moment | Any moment has been saved | Open the player for the loaded episode. If none is loaded, open a playable episode’s detail page. If the library has no playable episode, open Search. |
| Sync & backups (optional) | A Google sync binding exists or an automatic backup file is connected | Open Settings → Sync, including on backup-only builds. |

Before the first play, the card helps the listener find a show or an episode. After playback begins, feature **Set up takeaways** on the next visit to Library. Use active playback or a positive live or saved playback position as evidence of listening. Loading an episode alone keeps the pre-listening card visible. Keep playback running when the listener opens Settings. The Setup guide also exposes these actions on demand.

Under **Set up takeaways**, explain the two steps in listening order:

1. **Prepare the transcript on this phone.** Show the selected model’s name, size and download status. Open **Settings → Transcript preparation** for download, progress or pause. With Tiny installed and Base selected but missing, show **Download Base**. Network policy and the contextual notification prompt still apply.
2. **Generate the takeaway.** Open **Settings → ChatGPT**, with its existing sign-in, cancel and retry controls. Explain before connection that AI actions send selected text to OpenAI and use the listener’s plan allowance. The listener can already play audio and save a moment’s source while setup is incomplete. Once ready, point them to **Distill last 60 seconds** in the player.

Derive readiness from the current selection, model files and account state each time the card is shown, including after a restore or selection change. Hide the card while the core items are complete. Set the dismissal flag only when the listener chooses **Dismiss**. Optional Sync & backups never keeps it visible. Its checkmark indicates a connection. The Sync page shows backup failures and its reconnect action.

- A **More to explore** link opens a sheet pointing to headphone capture, the home-screen widget, Android Auto and, when the build enables it, Voice.
- **Settings → About DistillPod → Setup guide** shows the same list at any time, for every installation.

## Edge cases

- **Launch from an intent** (widget, media notification, update notice, voice search) while `pending`: the requested screen opens on top; setup remains underneath and is not marked done.
- **Sign-in left unfinished:** Settings → ChatGPT shows the recovery message that `AccountRepository` already produces; nothing is specific to setup.
- **Sync restores shows during the flow:** the Library fills in behind the flow. **Open library** shows the restored shows.
- **A skipped user who later restores a library:** card items complete on their own as data arrives.
- **Large text and TalkBack:** screens scroll, headings are announced, and the primary action stays reachable at 200% font scale.
- **Offline:** each action reports its own existing error. No screen blocks progress on network access.

## Privacy

Viewing, skipping or dismissing setup stores only the two device-local flags and does not initiate sign-in, file access or a backup. Choosing an action uses its existing permissions and storage. On recovery, explain that a managed backup contains readable personal text and feed addresses. Before the file picker opens, explain when connecting it enables recurring writes. Reuse the Settings disclosures for Google sync and ChatGPT. No usage event is recorded for setup steps.

## Implementation

| Area | Change |
| --- | --- |
| `core/` | Pure first-run decision and card-item derivation, with unit tests |
| `data/AppSettings.kt` | The two local flags; excluded from `PortablePreferences` |
| `features/OnboardingUi.kt` (new) | Welcome, recovery, the Library card and the More to explore sheet |
| `features/ProductApp.kt` | `onboarding:<step>` routes without bottom bar or mini-player; link to the existing ChatGPT and transcript settings; reuse the existing Sync and backup destinations with a return path to setup; card as the first Library item; Setup guide row in About |
| `features/AppViewModel.kt` | Hold the loading state until the first-run decision is read, then choose the initial route |
| `features/ManagedBackupUi.kt` | Show the recurring-write disclosure beside **Connect existing backup**, before launching the picker |
| `IsolatedTestRunner` | Force `done` and `cardDismissed` |
| `scripts/test_settings_organization.py` | Assert that onboarding sources contain no `vm.flag(` or `vm.text(` call |

Risk is concentrated in two places: the first-run decision, where a wrong answer shows setup to an existing user, and the initial route, where a wrong answer breaks every device test that starts on the Library.

## Mockup approval

Review the [screen mockups](evidence/onboarding/mockups.png) or open the [HTML walkthrough](evidence/onboarding/mockups.html) locally. Walk through both tasks before approving the screens.

1. Starting at Welcome, can a new listener find and play an episode immediately, then discover how to get a takeaway from the Library card?
2. Starting with only a backup file, can a returning listener recover their library without signing in to Google and explain, before choosing the file, when DistillPod can write to it and what canceling restore does?

## Validation

The Sync change was exercised on a Galaxy S22 with a backup file in Downloads. Background writes to Drive with the app closed and a later daily run under Doze were still unverified in the release notes. The checks below cover the proposed onboarding flow. [Validation recorded for #271](https://github.com/andrepaim/distillpod/pull/271)

- **Unit:** the decision table, including update installs and each single non-empty input; card visibility and prerequisite states, including Tiny ready with Base selected but missing, and Base becoming ready after download. Cover each destination for **Save your first moment**.
- **Device, isolated emulator:** a new `OnboardingDeviceTest` covering both paths, Skip from every screen, Back, process recreation on each screen, 160% and 200% font scale, TalkBack headings, the card's appearance and disappearance, and an update over a seeded database showing neither flow nor card. Include an upgrade with a remembered backup destination and verify that the destination and its schedule are preserved.
- **First listen on the isolated emulator:** go from Welcome to Search, play a fixture episode, open Library, and follow **Set up takeaways** to each Settings page. Check that playback continues. With nothing loaded, check that **Save your first moment** reaches a playable episode or Search in an empty library.
- **Sync and backup on the isolated emulator:** exercise the returning path with a configured test backend and a backup-only build. Seed a fixture provider with a managed archive containing one identifiable snapshot and use a different library on the test phone. Check the disclosure before the picker. Connect the archive, cancel the restore preview, and verify that the phone’s library was not merged. Let the first scheduled backup run with its constraints satisfied. Reopen the file, verify that a new snapshot was written and the original snapshot is still present, then restore the original without Google sign-in and return to Library. Verify that opening or skipping setup alone never creates a backup connection. Check that a connected file satisfies the optional card item without a Google account.
- **Existing suites** must pass unchanged, which is the evidence that the test-runner default works.
- **Manual, with task authorization:** live ChatGPT sign-in via the card and Google sign-in via recovery on an emulator. These are live account requests and are not run without the owner's approval.
- **Physical device:** installing the update over the owner's app must show nothing new. No uninstall or data clearing is involved.

## Documentation

Update [Getting started](../getting-started.md) so the first session follows the in-app flow, add this page to the [documentation index](../README.md), record the feature in [implementation status](implementation-status.md), and refresh the Library screenshot only if the card appears in it.

## Delivery

| Task and owner | Deliverable | Acceptance |
| --- | --- | --- |
| 1. Screen mockups · design agent | Welcome, recovery, pre-picker disclosure and the Library card before and after first playback, using the existing Android theme. | Review the two [approval walkthroughs](#mockup-approval) before implementing the screens. |
| 2. First-run decision · startup agent | Write failing decision-table tests. Read actual install, library, account and backup state before choosing the initial route. Persist the decision and dismissal atomically on the device. Default isolated tests to an existing install and let onboarding tests opt in. | A clean install reaches Welcome. Updating an empty or populated existing install reaches Library without a card. Backup and sync never transport these flags. |
| 3. Startup and navigation · startup agent | Preserve the saved back stack and incoming intents while the decision loads. Wire fresh, recovery, skip, finish and Back transitions. | Search opens directly from Welcome. A deep link retains its destination above pending setup. Rotation, task recreation and a second launch preserve the specified behavior. |
| 4. Guided actions and Library card · UI agent | Build Welcome, recovery and the contextual card. Derive action readiness from current playback, selected model, ChatGPT, follows and moments. Reuse Settings, the OPML picker and existing episode destinations. | Tiny installed with Base selected still offers Base. A first-moment tap always reaches the loaded player, a playable episode or Search. Dismissal and completion behave as specified. |
| 5. Backup disclosure and recovery · coordinator | Place the recurring-write disclosure before the picker. Exercise backup-only recovery with a seeded archive and distinct local library. | After canceling preview, the library remains intact. The first scheduled write preserves the original snapshot, which can still be selected and restored. |
| 6. Integration, documentation and validation · coordinator | Connect the card under Latest and the guide under About, review the combined routing and accessibility, update user docs, and run the validation above against the integrated revision. | Record red/green tests, isolated emulator results, in-place physical upgrade evidence and limitations. Reconcile current main and verify required PR and combined-commit CI before completion. |

For task 2, load the device-local decision first. If it is missing, wait for library initialization and read the database and durable account/file state. The empty lists initially emitted by UI flows can appear before an existing library loads. If initialization fails, suppress onboarding for that launch and preserve the option to retry the read. For task 3, test a playback or update intent arriving before the initial route is chosen and verify its requested destination survives restoration.

For task 4, resolve the selected model against its local file as well as its database status. Test recovery when a ready row refers to a missing file. In the guide, send **Save your first moment** to an episode available in the listener’s library before falling back to Search.

The coordinator serializes Gradle, emulator and phone use. The startup agent owns the route dispatcher and startup logic. The UI agent owns the new composables and card rules. The coordinator owns Library and About insertion points, backup disclosure, cross-feature acceptance and documentation.

## Open decisions

1. **Online transcript lookup.** Decide whether the takeaway guidance should link to the existing opt-in in Settings. It sends episode titles to a third party and saved moments still need the local model for timing.
2. **Setup card for existing installations.** Proposed: never, with **Setup guide** in About as the way in.
