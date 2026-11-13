# Queue controls (#54)

## Intended behavior and implementation
- Display the complete Media3 playlist in its actual order, marking the current episode in place. Every row offers accessible move-up/down actions, unavailable at the full-playlist boundaries (see [compact rows](#compact-rows-151)).
- Resolve moves by episode identity against the live controller. Preserve current ID, position, source and playback state; ignore missing identities and offsets other than one step.
- Serialize queue commands in tap order rather than dropping rapid actions. Controller events refresh displayed state immediately; position polling is unchanged.
- Expose clear-all semantics with explicit confirmation: **Clear queue and stop playback?** Cancel and Back do not change playback. A changed playlist dismisses stale confirmation.
- Confirm pauses and removes every item, including current. Clearing play intent prevents a subsequent enqueue from unexpectedly starting playback. Empty and single-item queues remain usable.
- Preserve listening progress and downloaded files. An old-item-only removal checkpoint uses Media3's reported old position through the existing serialized writer. It cannot restore a removed queue or overwrite its new session. Zero duration preserves stored duration; removal does not mark an episode completed. Same-ID source replacements and reorders do not count as removal.
- Keep existing database, backup and CollectionSync formats.

## Plan and merge gates
Start from current main in an isolated worktree; inspect queue, service and sync invariants before changing behavior. Add failing regression tests, implement the smallest integration, and review against refreshed main. Require relevant automated checks and disposable-emulator acceptance on the published head, followed by successful merged-main CI before closing #54. No release metadata or publication is part of this feature.

## Risk assessment
The affected behavior is foreground queue presentation, existing Media3 commands and serialized progress/queue persistence. Automated and disposable-emulator checks establish order, current-item retention, Room persistence and sync decision behavior. The handoff validation additionally requires isolated S22 acceptance of the affected foreground flows, preserving the owner's production installation and data. This does not certify hardware routing, Bluetooth gestures, background battery behavior or production upgrade compatibility.

Persistence remains asynchronous. Disposal before pending writes finish is an existing durability limitation; this change does not claim crash-proof synchronous persistence or greater precision than Media3's millisecond position. Reassess the device gate if scope expands into background lifecycle or platform-dependent behavior. Never run synthetic fixtures on personal installations.

## Regression coverage
- Full-list boundaries, current-item moves, empty/single queues, missing IDs and invalid offsets.
- Rapid FIFO commands, suspended/error commands and cancellation with pending work.
- Clear confirmation, Cancel/Back, externally replaced/emptied playlists and large text.
- Real ExoPlayer play-intent reset, later enqueue and repeated empty clear.
- Disposable-emulator playback/Room checks for current position/source, paused and playing moves, external controller order, clear retention, successor removal and paused enqueue after clear.
- The long local WAV fixture declares its actual duration and preserves/disables/restores automatic transcription so queue checks do not depend on preceding tests.

## TDD and validation evidence
Initial new API regressions failed before implementation. A behavioral test then demonstrated that external nonempty playlist replacement retained stale confirmation; resetting confirmation on playlist changes fixed it. A real ExoPlayer test demonstrated that clearMediaItems alone retained playWhenReady; pause-then-clear fixed it.

Focused tests pass, including eleven existing transcription tests and nine queue tests. Full final default and voice-enabled core/app JVM tests, lint, debug APK and instrumentation APK assembly passed after the clear-and-stop adjustment. Python tooling passed 87 tests; checksum-verified Gitleaks history and secret-rule regressions passed. Committed-diff security checks, hosted emulator acceptance and merged-main CI remain merge gates. The feature integrates main 9528abf: its only intervening change from c1ef31c is the production-triage guidance in AGENTS.md, with no application, test or build input changes.

## Repository-test isolation
A full-suite run encountered a pre-body AppGraph cancellation timeout in AutomaticTranscriptionTest. One targeted diagnostic run passed all eleven tests, so the historical surviving child remains unidentified.

That fixture intends to suppress startup work and exercise explicit repository calls. It now injects a pre-cancelled scope through an internal constructor, asserting an inactive/completed/childless scope and incomplete ready. The public AppGraph(context) constructor retains the same SupervisorJob + Main.immediate scope. Real settings, Room, WorkManager and all transcription assertions are retained; startup/service acceptance still uses normal application construction.

This removes startup-then-shutdown nondeterminism from the repository fixture. It is not proof of a production shutdown defect, and ready is not fabricated.

## Playing-position acceptance oracle
Hosted diagnostic run 42606815684 identified a test-oracle disagreement: the controller estimated 2084 ms while the service persisted 2010 ms. Media3 1.5.1 extrapolates controller positions from elapsed time and masks controller removal callbacks before the service handles the command. These observations do not establish a playback rewind.

The emulator fixture therefore observes the actual service ExoPlayer removal event and requires Room to match its old position exactly, with a same-player pre-clear progress floor. Paused 4567 ms and successor-removal 3456 ms assertions remain exact. A test-only helper reads the pinned Media3 session registry under its existing lock, releases that lock before player access, requires a unique synthetic episode and an ExoPlayer, and attaches/removes the listener on its application looper. A Media3 upgrade may require updating this explicit test coupling. No production access hook, fallback oracle, relaxed timeout or position tolerance is introduced.

## Restored-queue persistence
Normal cloud projection (and observer-first backup restore) can replace Room's queue while the running service is empty. Restoration suppresses playlist callbacks, so the old writer baseline could remain empty and incorrectly deduplicate the subsequent clear. The service now sends an ordered, read-only restoration observation through its existing snapshot channel before later Main-thread commands. Imported markers retain their existing temporary-active-item behavior. Periodic and source-only snapshots cannot publish a newer incoming queue; actual local order changes skip redundant writes when Room already matches.

This additionally touches queue persistence after supported cloud/backup restoration. It does not change sync formats, cloud permissions, migrations, lifecycle or scheduling. Required evidence includes local decision regressions (restoration then clear, marker/dedup, incoming queue protection), existing sync/backup tests and hosted real-service projection/clear acceptance. No-echo decisions are established by local regressions; the hosted isolated application intentionally disables production cloud mutation, so it does not establish live outbox behavior. No owner-device data or live cloud account is used. The isolated emulator gate and final merged-main CI remain mandatory.

The original single-list policy failed three focused restoration/startup/dedup assertions. The explicit restoration observation passes all five decision cases and six existing CollectionSync tests. Full default and voice-enabled validation each passed 99 core and 510 app JVM tests, lint, and both debug/instrumentation APK assembly. Hosted real-service acceptance remains pending; the preceding oracle-only run stopped in ManagedBackupsDeviceTest before queue tests executed.

## November 9 handoff validation

The preserved queue/restoration work and bounded backup diagnostics were integrated with main 377a2f3 as a2dd323. Fresh default and voice-enabled gates each passed 99 core and 520 app tests with zero failures, errors or skips, lint, and debug/instrumentation APK assembly. All 87 Python tests, committed data guard, credential-rule regressions, Gitleaks 8.30.1 full-history scan and whitespace checks passed.

All five hosted checks passed on a2dd323 in Android CI run 42615059627 and security run 42615059701. The ordinary device suite completed 80 tests, including both queue-control/service-removal and restored-queue acceptance; the remaining notification, Auto, sync and voice stages also passed. These are disposable API 35 emulator results, not phone or live-cloud mutation evidence.

The older ManagedBackupsDeviceTest failure establishes concurrent SnapshotStateObserver access during layout, but its original log lacks the competing thread's stack. The instrumented run completed both backup scenarios without an uncaught exception. The precise initiating test/framework call remains unconfirmed: successful rerunning does not establish a fix. Bounded phase/thread diagnostics retain the original handler and assertions for a future occurrence. No exception suppression, assertion weakening or dependency upgrade was introduced.

Main then advanced to 7a815ee through the separate release owner's PR #66. Its delta is release notes/version metadata only. This branch integrates that revision without independently changing release metadata. Fresh checks on the resulting head and successful combined-main CI are still required; a2dd323's evidence alone does not certify that later head.

A separate read-only local emulator instance failed socket initialization and was stopped without changing existing emulators, ADB, Windows/WSL services or settings. No local emulator or physical-device pass is claimed for this handoff.

## Isolated S22 acceptance protocol

`QueueControlsPhoneDeviceTest` is a separate opt-in fixture, excluded from the ordinary emulator class scan. The original emulator-only fixture and its physical-device refusal remain unchanged. The phone fixture refuses every target except `dev.distillpod.android.validation.queue54`, requires no INTERNET permission and a fresh empty library, and creates/removes only synthetic fixtures in that application's sandbox.

Use a dedicated offline validation build with matching test application ID and instrumentation target, no Sentry/Firebase configuration, and a copied public `jfk.wav` fixture from the pinned submodule. Verify immutable APK copies and their hashes, both package identities, instrumentation target, signatures and absent INTERNET permissions before installation. Acquire `/tmp/distillpod-s22-validation.lock`; require an unlocked/awake S22 with no active playback or instrumentation. Never install either APK over `dev.distillpod.android`, change global volume/settings, clear owner data or modify other validation apps.

The selected test checks paused and playing UI moves, current identity/source/position, boundary controls, confirmation cancellation, confirmed clear-and-stop, persisted progress/session/queue, retained files/download metadata, and a paused enqueue afterward. It mutes only its own validation player and restores its volume/preferences. Preserve the production package metadata before/after; stop and remove only newly installed validation packages after success. Keep failed fixtures stopped for investigation. Physical results remain pending until this exact protocol runs on the final reviewed revision.

## Labels, focus and incremental enqueue (#144)

- **Time left.** Queue rows, library cards, the resume card and the mini-player share one rule. The current episode uses the player's duration, and other episodes use the stored duration. Feeds with dynamic ad insertion can declare a different length than the audio that plays. Playback checkpoints already persist the measured duration. A later feed refresh can restore the declared value; that convergence is tracked separately.
- **Played.** A finished episode reads "Played" instead of "0 min left" or "1 min left". This applies when it is not current, or when it is current and at its end. Replaying it shows its remaining time again.
- **Sections.** The queue keeps the real Media3 order, split into *History*, *Now playing* and *Up next*, with a "Nothing up next" hint. It opens on *Now playing* when earlier items exist. Later edits on the screen keep the user's scroll position. Rows are indexed rather than looked up with `indexOf` per row.
- **Enqueue.** *Play next* and *Add to queue* call `addMediaItem` / `moveMediaItem` around the current item. They no longer rebuild the playlist with `setMediaItems`, which released and re-prepared the current source (an audible gap, a new request, and a position captured before an asynchronous round trip). An already-queued episode moves instead of being duplicated. Only an empty or restored, unprepared playlist is prepared, and play intent is unchanged. `move` and `remove` already used `moveMediaItem` / `removeMediaItem`. Android Auto additions go through `onAddMediaItems`, which only resolves items; Media3 then applies `addMediaItems`.
- **Evidence.** Robolectric Compose tests cover labels, section order, initial focus and the empty hint. A real-ExoPlayer JVM test records player commands and listener events. The previous rebuild failed it with a REMOVE discontinuity, a playlist transition and BUFFERING. The emulator and the isolated S22 queue fixtures attach to the actual service ExoPlayer during a while-playing enqueue. They require the same current `MediaItem`, continued playback, a non-decreasing position and no discontinuity, transition or state change.

## Compact rows (#151)

- **Layout.** Each queue row is one line of content: position, cover, title (two lines at most), show and time left, then Play/Pause and a *Queue actions* overflow button. The separate move-up, move-down and remove buttons are gone, so a short-title row is about half its former height.
- **Overflow menu.** *Queue actions for {title}* opens **Move up**, **Move down** and **Remove from queue**. Full-playlist boundaries are unchanged: the first item cannot move up and the last cannot move down, so those entries are disabled.
- **TalkBack.** The row exposes the same edits as custom accessibility actions, labelled "Move up", "Move down" and "Remove from queue". Boundary moves are omitted rather than offered and ignored. Opening the row and Play remain separate actions.
- **Commands.** Both paths call the existing `AppViewModel.moveQueue` / `removeQueue`, which serialize `PlaybackRepository.move` / `remove` (`moveMediaItem` / `removeMediaItem`) by episode identity. Current item, source, position, play state and the removal checkpoint are unchanged.
- **Gestures.** Drag-to-reorder and swipe-to-remove were not added. A swipe can remove an episode by accident, and both gestures would still need the menu and custom actions as non-gesture alternatives.
- **Evidence.** Robolectric Compose tests cover the row height and single control line, the custom-action sets at each boundary, menu enablement, moves/removal by identity, external queue changes, and large text (2×) on a 320 dp screen. The emulator fixture uses the menu for a paused move and for removing the current item, and the platform accessibility tree for a move while playing. The isolated S22 fixture uses the menu and the platform accessibility action.
