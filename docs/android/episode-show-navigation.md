# Episode-to-show navigation (#49)

## Scope and acceptance

Both the expanded player and selected episode details expose a labeled **View show**
button alongside the show title. The button opens the existing show screen by
pushing its route onto the current back stack. It does not call playback, queue,
seek, subscription, or refresh actions. Back returns to the entry point.

When the episode's show is absent from the current library snapshot, the header
says **Show unavailable** and exposes no link. The link appears when that show
becomes available. A missing episode retains the existing unavailable-episode UI.

This is a local UI/navigation change. It changes no persisted data, service
lifecycle, account, permissions, backend, signing, or release configuration.

## Validation plan and evidence

- TDD: `EpisodeShowLinkTest` first failed both tests on the inert original-title
  stub (missing labeled action and unavailable fallback). The implemented shared
  control is covered for its accessible name/click action, destination, missing
  destination, recovery, and removal.
- `ListeningDesignDeviceTest` adds paused-player navigation and repeated Back,
  then selects an episode from a different show while playback continues.
  It checks the destination, current episode, queue, position, speed, and return
  route. These cases run in the existing disposable-emulator CI suite, without
  a live account/provider.
- Local checks: core tests, app unit tests, lint, debug APK assembly, and
  instrumentation APK assembly. See the PR for exact tested revision/results.
- An independent read-only review found no actionable correctness issues.
- No local emulator or owner's phone was used for this change. Physical-device
  acceptance remains a merge gate under the repository policy in force at
  implementation time. A later merged policy must be evaluated explicitly;
  a proposed policy is not a waiver.

No release or APK publication is part of this work.

