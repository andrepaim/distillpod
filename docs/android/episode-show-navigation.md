# Episode-to-show navigation (#49)

## Scope and acceptance

The expanded player shows the muted show name under the episode title, and selected
episode details expose a **View show** button alongside the show title. Both are
labelled "View show: <show>" for TalkBack. Each opens the existing show screen by
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
- Green combined revision: `03c10e81bd24a7b64badb26d6a0d8e7358843942`.
  [Android CI](https://github.com/andrepaim/distillpod/actions/runs/42540504756)
  passed Kotlin/lint/APK, native, Firebase, and isolated emulator acceptance.
  The emulator log confirms the navigation regression ran and passed.
- Final integration incorporates policy PR #50 at main `04754ade`. Only
  `AGENTS.md`, `docs/development.md`, and this evidence document differ from
  the green revision; production code, tests, resources, dependencies, and
  build/CI scripts remain identical. Automatic PR CI validates the new head.

## Reviewed risk assessment after policy integration

This is low hardware/platform risk under the merged per-change validation
policy. Both entry points add the same local Compose control and call the
existing navigation stack. No new playback or service mutations, audio routing,
background behavior, runtime permissions, account operations, persistence,
migration, or upgrade/signing changes are introduced. Existing video-mode
navigation behavior is unchanged.

The passing emulator coverage verifies both entry points, a selected episode
from a different show, paused and running playback, current episode, queue,
position and speed invariants, repeated navigation, toolbar and system Back,
and large-font visibility. Local Compose tests cover interrupted show
availability (absent, restored, then removed) and accessible action semantics.
Independent review found no actionable correctness issues.

These automated and emulator checks are sufficient for the changed local
navigation behavior. No physical phone is required for this change, and none
was accessed. OEM/hardware routing and background reliability were not retested
and are not claimed by this evidence; their existing feature-specific gates
remain applicable to changes affecting them.

No release or APK publication is part of this work.
