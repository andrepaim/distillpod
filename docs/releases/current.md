DistillPod 0.32.0-alpha.1 tidies the Library: **Latest** lists only shows you follow, and the "shows couldn't refresh" warning can be swiped away.

- **Unfollowed shows leave Latest.** Before, a downloaded or already played episode from a show you had unfollowed stayed in **Library → Latest**. Now Latest lists episodes from followed shows only, and its count matches the list. Nothing is deleted: downloaded episodes stay under **Downloaded** and play as before, unfinished ones stay under **In progress**, and transcripts and saved moments are kept. Following the show again brings its episodes back to Latest ([#266](https://github.com/andrepaim/distillpod/issues/266)).
- **Swipe right to dismiss the refresh warning.** The Library's "N shows couldn't refresh" warning now goes away with a rightward swipe, and the episodes below move up into its space. A short swipe springs back, and **Show details**, **Retry** and scrolling still work. TalkBack offers **Dismiss warning** instead of the swipe ([#264](https://github.com/andrepaim/distillpod/issues/264)).
  - Dismissing only hides the warning. The shows stay followed and saved episodes are kept. Refreshes and retries carry on as usual.
  - The warning stays hidden while the same shows keep failing, including failed retries and app restarts.
  - It comes back if another show fails, listing only that show, or if a dismissed show refreshes successfully and later fails again.
  - A show's own page still shows its refresh error.

Install the signed APK over the existing app; do not uninstall first. This personal alpha keeps application ID `dev.distillpod.android` and the existing signing identity, and raises the version code to **56**. Database schema, backup format and Firebase rules/protocol are unchanged from 0.31.0-alpha.1. Which warnings you dismissed is stored on this phone only; backups and sync don't include it. Updating from 0.29.0-alpha.1 or earlier also brings everything in [0.30.0-alpha.1](https://github.com/andrepaim/distillpod/releases/tag/v0.30.0-alpha.1) and [0.31.0-alpha.1](https://github.com/andrepaim/distillpod/releases/tag/v0.31.0-alpha.1), including 0.30's additive database upgrade. Downgrading the APK is not supported.

**Known limits:**

- **Continue listening** at the top of the Library can still offer an unfinished episode from a show you unfollowed. Only the episode list under Latest changed.
- **The swipe has no visible button.** Without TalkBack, the warning can only be dismissed by swiping. In right-to-left languages the swipe goes toward the end edge, which is leftward.
- **Why feeds fail to refresh** is a separate question that this release doesn't address. Dismissing the warning doesn't fix the feeds.
- **Not tried on a phone.** Both changes were validated with automated tests and the isolated emulator suite, not on a physical device.
- **Still open from earlier releases:** the limits listed in the [0.31.0-alpha.1](https://github.com/andrepaim/distillpod/releases/tag/v0.31.0-alpha.1) and [0.30.0-alpha.1](https://github.com/andrepaim/distillpod/releases/tag/v0.30.0-alpha.1) notes, including [#251](https://github.com/andrepaim/distillpod/issues/251).

**Validation:**

- **Hosted CI:** both fixes ([#267](https://github.com/andrepaim/distillpod/pull/267), [#268](https://github.com/andrepaim/distillpod/pull/268)) passed full CI, including builds, lint, Firebase rules, native checks and isolated API 35 acceptance. #268 passed again after being brought up to date with #267.
- **New tests:**
  - Latest hides the downloaded, partly played and finished episodes of an unfollowed show, while Downloaded, In progress and the show's page still list them; following again restores them.
  - Swiping inside a Library-shaped list: a full swipe dismisses the warning and collapses its space; a short or leftward swipe doesn't; taps and scrolling still work; the TalkBack action dismisses.
  - A dismissed warning stays hidden for the same failures and comes back for a new one.
- **Publication:** the release workflow separately reruns full validation, release lint/assembly and signing. The downloaded assets are then checked for checksums, source identity, package/version, non-debuggable state, retained certificate and alignment.

[View changes since 0.31.0-alpha.1](https://github.com/andrepaim/distillpod/compare/v0.31.0-alpha.1...v0.32.0-alpha.1).
