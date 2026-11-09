# Queue controls (#54)

## Intended behavior and implementation
- Display the complete Media3 playlist in its actual order, marking the current episode in place. Every row has accessible move-up/down controls disabled at the full-playlist boundaries.
- Resolve moves by episode identity against the live controller. Preserve current ID, position, source and playback state; ignore missing identities and offsets other than one step.
- Serialize queue commands in tap order rather than dropping rapid actions. Controller events refresh displayed state immediately; position polling is unchanged.
- Expose clear-all semantics with explicit confirmation: **Clear queue and stop playback?** Cancel and Back do not change playback. A changed playlist dismisses stale confirmation.
- Confirm pauses and removes every item, including current. Clearing play intent prevents a subsequent enqueue from unexpectedly starting playback. Empty and single-item queues remain usable.
- Preserve listening progress and downloaded files. An old-item-only removal checkpoint uses Media3's reported old position through the existing serialized writer. It cannot restore a removed queue or overwrite its new session. Zero duration preserves stored duration; removal does not mark an episode completed. Same-ID source replacements and reorders do not count as removal.
- Keep existing database, backup and CollectionSync formats.

## Plan and merge gates
Start from current main in an isolated worktree; inspect queue, service and sync invariants before changing behavior. Add failing regression tests, implement the smallest integration, and review against refreshed main. Require relevant automated checks and disposable-emulator acceptance on the published head, followed by successful merged-main CI before closing #54. No release metadata or publication is part of this feature.

## Risk assessment
The affected behavior is foreground queue presentation and existing Media3 commands. The removal checkpoint changes an event/data path, not service lifetime, scheduling, hardware controls, audio routing, permissions, schema, migration or signing. Suitable JVM and disposable-emulator evidence can establish order, current-item retention, Room persistence and existing sync compatibility without an owner phone. Hardware/OEM behavior is unchanged and is not newly certified.

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
