# Android Auto implementation plan

> Implemented design with remaining acceptance gates. Android Auto is on `main`
> and in published alpha APKs. Use the [current setup guide](android-auto.md);
> branch names and device results below identify historical checkpoints.

Date: November 5, 2026. Status: core implementation and automated service checks completed on `feat/android-auto`; signed S22/Android Auto preview exercised. Corrected signed preview installed and launcher-enabled on the S25; physical-car acceptance remains open.

Make DistillPod available in the Android Auto launcher when the owner connects the S25 Ultra to a compatible car. The car should browse the existing library, resume episodes, play downloads offline, and save moments through a Distill action. The phone remains responsible for playback and storage.

Use the standard Media3 media-library integration and Android Auto's supplied interface. This fits the existing player and allows testing the privately installed APK. Keep Firebase on Spark and generated transcripts local; this feature requires no new backend or paid service.

## Scope and delivery

The first complete version includes discovery, four library sections, artwork, playback and resume, voice requests for the local library, Distill, connection recovery, and verification on the S25. Real-car verification is a separate recorded completion gate requiring access to the owner's car.

Android Auto projects the phone experience onto a compatible head unit. An independently installed Android Automotive OS app is outside this version. So are custom Car App Library screens, car-based podcast subscriptions, transcript reading, chat, authentication flows, and a custom spoken “Distill this” command. A media-session Distill button does not automatically register a voice command.

Milestones:

| Milestone | Result | Completion evidence |
| --- | --- | --- |
| A | DistillPod appears and browses in Desktop Head Unit | Discovery and library tests plus head-unit screenshots |
| B | Complete listening and Distill experience | Playback, voice, offline, capture and lifecycle tests |
| C | Verified signed S25 upgrade | Signature, version, preserved library and phone regression checks |
| D | Verified use in the owner's car | Recorded actual USB test; wireless test if supported |

Milestone A is an integration checkpoint, not the finished feature. If the car is unavailable, complete A–C and explicitly leave D pending.

## Current code and integration boundaries

The feature branch is `feat/android-auto`, checked out in `/home/andrepaim/src/distillpod-android-auto`. It started from updated main `069a1d0`, then incorporated completed social-sharing commit `93a2238` to retain the preview already installed on the S22. Concurrent work uses the original checkout on `feat/social-sharing`; this task builds and records changes only in its isolated worktree. A named stash retains the earlier implementation snapshot, and existing stashes were preserved.

Media3 remains on 1.5.1. Modern and legacy browser IPC, subscription updates, authoritative selection, resume, seeking and paused capture have passed synthetic emulator checks. The retained compact lock-screen control definitions were reconciled from saved work. The actual car interface exposed a legacy seek permission mismatch, which now has a regression test and a fix.

| Existing component | What we can reuse | Required change |
| --- | --- | --- |
| `PlaybackService.kt` | ExoPlayer, audio focus, noisy-route handling, queue restoration, checkpoints, reviewed ad skipping | Upgrade `MediaSessionService`/`MediaSession` to library equivalents and add callbacks |
| `playback/PlaybackRepository.kt` | Phone controller, media item mapping, play/enqueue/seek behavior | Extract shared selection rules so car and phone cannot diverge |
| `data/AppDatabase.kt` | Podcasts, episodes, queue, session, progress and downloads | Add bounded browse/search queries where needed |
| `data/CaptureRepository.kt` | Transactional moment insertion, duplicate suppression, source checks, existing processing | Separate durable acceptance from preparation; recover accepted pending work |
| `features/AppViewModel.kt` | Current phone capture behavior | Reuse the same capture coordinator; keep navigation phone-specific |
| `AndroidManifest.xml` | Exported playback service and foreground permissions | Add media-library discovery and Android Auto metadata |

The inspected service has no browsing callbacks or automotive declaration. Existing lock-screen controls may also exist in retained work or the installed build; reconcile them against the chosen baseline before implementation. Do not assume all installed behavior is present in this checkout.

Keep the existing `PlaybackService` component name and one player/session. Avoid creating a second player, queue, or car database. No schema change is expected for browsing; a capture-recovery change may need one, in which case schema export and migration tests become required.

## Current implementation checkpoint

Implemented: catalog and continuation folders, asynchronous browser/search/selection callbacks, startup coordination, subscriptions, voice request matching, queue/resume policy, −10s/+30s actions, durable Distill acceptance and recovery, cached thumbnails, compatible lock-screen controls, media-search intents, emulator CI integration and a signed S22 preview. See [TDD evidence](evidence/android-auto-tdd.md), [validation](evidence/android-auto-validation.md) and the [user guide](android-auto.md).

Milestone A was demonstrated with the S22 and official Desktop Head Unit: DistillPod appeared in the launcher and opened the four sections. Local/service checks passed. During later S22 checks another task replaced the phone build with a social preview. The owner then supplied the S25; the corrected signed preview was installed there, all library tables/preferences were preserved, and Android Auto listed and enabled DistillPod for the owner’s car test. Physical car, full assistant speech routing, long locked playback, interruption hardware and 10,000-episode target measurements remain open.

## Car experience

Android Auto supplies the layout and can vary controls by host and screen. We supply content structure, metadata, icons, and supported actions. [Google's media design guidance](https://developers.google.com/cars/design/create-apps/media-apps/overview)

| Section | Content and order | Selection behavior |
| --- | --- | --- |
| Continue listening | Incomplete episodes with progress; current episode first, then most recently played | Resume saved position using the shared queue policy |
| Queue | Existing queue in its current order | Select that episode without duplicating or reordering the queue |
| Downloads | Episodes with usable local audio, newest first | Play the local file, including without connectivity |
| Podcasts | Subscribed shows alphabetically, with cover artwork | Open a show's episodes, newest first |

Use short titles, show names, and host-supported progress/completion/download indicators. Prefer show artwork for show folders and episode artwork with a show fallback for episodes. Missing artwork gets a bundled placeholder. Cache bounded artwork for offline display; cover retrieval must not block browsing or audio.

Use host content-style hints for show grids and episode lists, accepting host overrides. Episode lists must remain navigable with touch and rotary controls. Bound results and use supported paging; if a host cannot expose all pages, add tested “More episodes” folders rather than silently making older content inaccessible. Keep ordinary paths shallow.

Empty states are explicit: no subscriptions, no downloads, no queued episodes, or no unfinished episodes. Any phone-only setup instruction says to use the phone when parked. Basic browsing and playback work signed out.

### Playback behavior

- Play/pause always reflects the shared player state.
- Request −10 seconds and +30 seconds, clamped to the seekable range. Disable unavailable seek actions.
- Keep next/previous episode semantics distinct from seeking, including steering-wheel commands. Do not globally remap next-track to +30 seconds.
- Selecting an episode already queued selects its existing position. A new episode follows the phone's shared insertion policy. A completed episode selected explicitly starts again; an unfinished one resumes.
- Connection or library browsing alone does not start playback. Explicit play and host-requested resume can start it.
- Restore queue, episode, position and speed after ordinary process recreation. User force-stop is a separate Android state; do not promise automatic restart after a deliberate force-stop.
- Prefer an existing valid local file. If it disappeared, use the normal streaming policy when online or return an actionable offline error. Do not change download preferences or silently download full episodes.

Advertise supported standard seek actions and a custom Distill command, retaining explicit legacy seek actions for hosts that render the notification controls through the Media3 APIs available in the selected version. Validate the legacy bridge in the head unit before committing to a particular button arrangement. Android Auto may place Distill in overflow. Exact compact-card placement is not an acceptance promise. [MediaSession controls](https://developer.android.com/media/media3/session/control-playback), [car playback controls](https://developer.android.com/training/cars/media/enable-playback)

### Distill behavior

Pressing Distill saves the current playback moment using the existing capture window, currently the preceding 60 seconds clamped to the episode start. It must work while paused, offline, and without AI authorization. Saving succeeds independently of eventual transcription or insight generation.

Read episode ID, position and source together from the service-owned player on its application thread at command receipt. Never use a stale UI snapshot or a timestamp supplied by an external controller. Preserve existing source verification for streaming audio and transcripts; unresolved source alignment remains reviewable on the phone.

Commit the saved moment and its existing portable-sync intent together, then acknowledge success. Schedule preparation outside the car callback. The inspected `capture()` calls `hydrate()` before returning, so simply invoking it directly is insufficient. Refactor into a shared coordinator with durable pending state and idempotent recovery after death between insertion and scheduling. Do not report failure merely because later processing is unavailable after a successful save.

Debounce accidental repeated commands with a tested policy, preserving intentional saves at different positions. During a save show a supported busy/disabled action state; on success use a brief supported “Saved” state. Do not open an Activity, overlay a transcript, or read an AI response aloud. Feedback returns to the normal Distill action afterward. Only acknowledge a committed save; storage failure produces a short retryable error.

Background processing follows existing preferences and authorization. No new microphone permission, cloud transcription, or bulk model/audio downloads are introduced by car connection. A missing prerequisite leaves the moment saved for later. The portable moment continues to obey existing sync policy; transcripts remain local.

### Voice requests

Support browse-search and play-from-search separately. Handle show names, episode titles, appropriate media extras, and empty requests. Match against the local library, including accented and mixed-case names. Rank exact title matches before partial matches, with deterministic tie handling.

For a show request, resume its most recently played unfinished episode; otherwise choose its newest unplayed episode. If all are completed, choose its newest episode. For a generic empty play request, resume the saved session, then use the first queued episode, then the newest unplayed episode. A missing match returns a clear result without interrupting current playback. Do not fetch unrelated directory results or subscribe automatically.

Android Auto delegates speech recognition to Gemini or Google Assistant. Local search and callback handling can be tested offline; speech recognition itself may require connectivity. Verify actual voice delivery on the available assistant and record the other as unverified if unavailable. [Voice integration](https://developer.android.com/training/cars/media/voice-actions)

## Technical design

### Service discovery and compatibility

- Declare `com.google.android.gms.car.application` referencing `res/xml/automotive_app_desc.xml`, containing `<uses name="media"/>`. [Android Auto manifest requirements](https://developer.android.com/training/cars/media/auto)
- Extend `MediaLibraryService` and create a `MediaLibrarySession` around the existing player.
- Advertise `androidx.media3.session.MediaLibraryService` and `android.media.browse.MediaBrowserService`. Retain the existing session action if needed for current clients and test discovery. The platform action enables older browser clients used by car integrations. [Media3 library service](https://developer.android.com/media/media3/session/serve-content)
- Preserve foreground-media permissions, notification behavior and session Activity for phone use. Review any voice intent declarations required by the selected integration.
- Validate APIs against the pinned Media3 1.5.1 dependencies. If required fixes or compatibility need a newer stable release, upgrade all Media3 modules together in an isolated tested commit; do not copy current documentation APIs into 1.5.1 unchecked.

### Browse catalog and request resolution

Introduce a small `AutoCatalog`/policy layer with stable folder IDs and existing canonical episode IDs. Folder IDs must not collide with episode IDs, change on refresh, or include account tokens or audio URLs.

Implement root, children, item lookup, subscription updates, search and search-result callbacks. Honor relevant recent/offline/suggested root hints with a documented fallback. Validate pagination, stale IDs, missing rows and cancellation. Mark folders browsable and episodes playable with appropriate media metadata.

Use asynchronous Room access and bounded pages. Apply a stable secondary ordering key. Observe library, queue, progress and download changes to invalidate affected folders; coalesce progress-driven updates instead of rebroadcasting the tree each second. Cancel observers and pending requests when their owner is destroyed. Confirm the legacy client receives changes without reconnecting.

Resolve selection by media ID inside the service; car clients may send IDs without playable URLs. Handle `onAddMediaItems`, `onSetMediaItems`, search-query request metadata, and playback resumption as supported by the selected Media3 version. Build actual playable items from authoritative repository data. Share queue/position policy with the phone without creating a controller-to-service recursion. [Media request resolution](https://developer.android.com/media/media3/session/control-playback)

Wait for application/database readiness before answering data-dependent requests. Coordinate startup restoration with incoming play commands so a delayed restore cannot overwrite a newer user selection. Keep player operations on the player thread and database/network work off it.

### Controller access and lifecycle

Preserve validation of connecting controllers and verify it with real Android Auto and legacy clients. If the current own-package-or-trusted check rejects a legitimate host, establish the appropriate documented UID/signature trust rather than accepting every caller or trusting an asserted package name. Allow only the necessary browse, playback, and capture commands.

External clients cannot submit arbitrary file paths, media URLs, capture timestamps, or internal diagnostic clips. Preserve any internal fixture/diagnostic playback through an explicitly scoped internal route. Test phone, system controls, Bluetooth, legacy browser and car callers independently.

Keep current audio-focus and noisy-route behavior, checkpoint persistence, download file protection and ad skipping. Verify interruptions do not resume audio after a user pause. Disconnect should preserve the session and avoid unexpected speaker playback where Android reports route loss. Reconnection restores metadata promptly and follows the host's play request rather than guessing whether the car wants autoplay. [Background playback lifecycle](https://developer.android.com/media/media3/session/background-playback)

## Implementation tasks and TDD sequence

For every behavioral task: write a failing test, record the relevant failure, implement the smallest change, run the focused tests, then refactor. Validate configuration through package/service discovery tests. Keep a concise red/green receipt in `docs/android/evidence/android-auto-tdd.md`; never mark unexecuted device checks as passed.

### Phase 0 Establish the baseline

- [x] Create an isolated feature worktree from the agreed integrated baseline; record commit and pending dependencies.
- [x] Reconcile installed lock-screen behavior and pending transcript changes; define the regressions this feature must preserve.
- [ ] Run the relevant existing playback, queue sync and capture tests once to establish baseline failures.
- [x] Inventory API support in pinned Media3; settle any dependency upgrade before implementing callback details.
- [x] Set up synthetic library/audio fixtures and a legacy browser/controller test client.

Exit: reproducible build and an explicit baseline, with no owner-library mutation.

### Phase 1 Catalog and policies

- [x] Add failing tests for the four sections, deterministic ordering, valid downloads and resume eligibility.
- [ ] Add failing tests for stable IDs, pages, duplicate titles, missing rows, empty folders and search ranking.
- [x] Implement bounded Room queries and pure catalog/selection policies.
- [ ] Add metadata, completion/progress hints, cached artwork and placeholder behavior.
- [ ] Test large-library behavior with at least 10,000 synthetic episodes and long/non-Latin titles.

Exit: catalog and policy tests pass; every supported section is accessible without network access.

### Phase 2 Discoverable library service

- [x] First test package discovery and connections through both Media3 and the platform/legacy browser interface.
- [x] Upgrade the existing service/session, declare automotive support and implement browse callbacks.
- [x] Implement controller validation and reject unauthorized operations with typed results.
- [x] Add folder subscriptions, invalidation and bounded asynchronous responses.
- [ ] Verify first connection with no Activity, no sync sign-in, an empty library and delayed app initialization.
- [x] Confirm actual launcher appearance and initial browsing in Desktop Head Unit.

Exit: milestone A; phone playback still passes its existing tests.

### Phase 3 Playback and voice

- [x] Write failing tests for ID-only requests, queue preservation, completed restart and saved-position resume.
- [x] Implement shared phone/car selection policy and service-side media resolution.
- [ ] Test startup restore racing a newer selection; ensure the newer request wins.
- [ ] Wire −10/+30 standard seeking, play/pause, queue selection and supported episode navigation.
- [ ] Add failing tests for browse search, legacy play-from-search, query request metadata and empty/no-match requests.
- [x] Implement voice resolution and playback resumption.
- [ ] Verify actual head-unit controls, seek intervals and assistant routing, with host versions recorded.

Exit: browsing, voice selection and playback use one coherent queue and progress model.

### Phase 4 Distill

- [ ] Add failing tests for capture at command-time position, beginning/end boundaries and transition races.
- [x] Separate transactional saving from preparation while preserving source verification and phone behavior.
- [ ] Implement durable pending-work recovery and idempotent dispatch; test death before and after enqueue.
- [ ] Add a scoped session command, icon, availability state and visible acknowledgment supported by the host.
- [ ] Test repeated commands, offline state, missing transcript/model/authentication, source mismatch and storage failure.
- [ ] Confirm saved moments appear on the phone without requiring it to have been open during capture.

Exit: Distill acknowledges a durable save promptly and never blocks on transcription or AI.

### Phase 5 Reliability and regression checks

- [ ] Exercise the matrix below on disposable test installs and Desktop Head Unit.
- [ ] Verify notification and lock-screen controls, headset controls, phone seeking, queue sync and approved ad skipping.
- [ ] Check callbacks for main-thread database/network work and observer leaks.
- [ ] Review applicable media criteria in Google's current car-quality checklist, including voice, response time and errors. [Car quality requirements](https://developer.android.com/docs/quality-guidelines/car-app-quality)
- [x] Add focused tests to existing CI; keep physical car tests as recorded manual gates.
- [x] Document setup, controls, offline limits, troubleshooting and known host differences.

Exit: milestone B; no unresolved data-loss, playback, capture or car-discovery defects.

### Phase 6 Signed phone upgrade and car validation

- [x] Build a versioned release APK from the exact reviewed source, record checksum and signing certificate.
- [x] Save a private owner-library backup and verify the existing signing identity; install with replacement while retaining data.
- [x] Verify subscriptions, progress, queue, saved content, preferences and local transcript availability after upgrade; investigate differences rather than claiming preservation solely from row counts.
- [x] Enable Android Auto's media-app development setup if needed and confirm the S25 lists DistillPod.
- [ ] Complete Desktop Head Unit checks and leave the owner app ready for use.
- [ ] Run the real-car checklist below when the car is available.
- [ ] Attach sanitized evidence, finish the PR and use the existing release process when publishing is requested.

Exit: milestone C on the phone and milestone D only after actual car evidence.

## Test matrix

| Layer | Cases | Required assertions |
| --- | --- | --- |
| JVM policies | Sections, sorting, paging, Unicode search, empty query, ambiguity, stale IDs | Deterministic results; no duplicate items or inaccessible tail content |
| Room integration | Catalog queries, download disappearance, subscriptions, process recovery | Correct results and durable saved state; no network dependency |
| Media3 client | Browse, select, seek, resume, custom command, rejection | Correct item/position/queue and command permissions |
| Legacy client | Root/children, item selection, search, updates, custom action | Real compatibility bridge succeeds; not just direct callback tests |
| Lifecycle | Service before Activity, locked screen, task dismissal, process kill, restart | No duplicate player, overwritten new request or lost accepted capture |
| Offline/errors | Airplane mode, slow network, broken URL, missing file/art, empty library | Downloaded audio works; bounded response and useful errors |
| Interruptions | Navigation prompt, call, focus loss, manual pause, cable loss | Correct pause/duck policy, no unintended resume or speaker continuation |
| Concurrent actions | Phone/car seek, queue edit, capture at episode transition | Consistent final state and capture tied to the intended episode |
| Capture recovery | Double tap, DB failure, commit-before-enqueue death, auth/model unavailable | One intended save; committed work recoverable; honest feedback |
| Upgrade/regression | Existing phone build, lock screen, sync, transcription/ad skipping | No lost library or regression in existing behavior |
| Head unit | Small/large display, light/dark mode, touch/rotary, overflow | Readable titles/icons, reachable sections, discoverable Distill |
| Voice | Show/episode/empty/no-match, Gemini and Assistant paths | Correct callback handling; actual available assistant verified |

Proposed product targets on the S25 with a 10,000-episode fixture: warm local browse/search p95 under 500 ms, cold catalog availability under 2 seconds in normal conditions, and visible local control/capture response within 2 seconds. Record build, device, sample count and distribution; network buffering and transcript generation are measured separately. These are targets to validate, not existing measurements. The two-second app-button requirement is also part of Google's car-quality criteria.

Run one scheduled 60-minute screen-locked session using downloaded audio after shorter checks pass. Sample progress and player health automatically, include seek/capture checkpoints and reconnect near the end, and report progress during the run. Separate forced process-death tests use fixtures; they do not run against the owner's personal library. Do not leave an unexplained hour-long task running.

## Desktop Head Unit and real car procedure

The Desktop Head Unit (DHU) simulates an Android Auto head unit and supports connection to a real phone. Use it for Android Auto validation; an Android Automotive emulator alone does not prove projection works. [Official DHU setup](https://developer.android.com/training/cars/testing/dhu)

For this workspace, use the Windows Android SDK/ADB connection that can see the S25 over USB. Keep ADB, forwarding and DHU on the same host to avoid the existing Windows/WSL connection confusion. Select the phone by serial when multiple devices are present. Follow the current DHU instructions for head-unit server and connection setup.

For the privately installed media APK, enable Android Auto developer mode and its “Unknown sources” option. This exception applies to standard media apps; it is another reason to use the browser-service approach for the first version. A Play internal test track or Internal App Sharing is an alternative later. [Official car testing instructions](https://developer.android.com/training/cars/testing)

Actual car checklist, performed while parked or with a passenger operating the interface:

1. Connect the S25 by USB; DistillPod appears in the launcher, including Customize launcher if required by the host.
2. Open every section, inspect artwork, and select a downloaded episode without opening DistillPod on the phone.
3. Check play/pause and measured −10/+30 seeking; inspect next/previous and steering-wheel behavior separately.
4. Save a Distill moment while playing and while paused. Later confirm episode, time window and pending/completed state on the phone.
5. Disconnect, lock the phone and reconnect; verify queue, position and host-requested resume.
6. Test downloaded playback with connectivity unavailable and a streaming request that cannot load.
7. Verify navigation/call interruption behavior and that a deliberate pause remains paused.
8. Exercise voice requests using the available assistant; record recognition or app-routing failures separately.
9. If the car supports wireless Android Auto, repeat connection, capture and disconnect checks wirelessly. Otherwise record wireless as untested.

Car model, head-unit firmware, Android version, Android Auto version and assistant availability are needed at this stage, not to begin implementation. Screenshots/logs must omit personal transcripts, account details and credentials.

## Release, recovery and completion

No new cloud deployment or billing change is part of this plan. Use existing consent-respecting diagnostics if available; otherwise keep timing and failure evidence local. Avoid logging episode titles, search queries, transcript text, tokens or raw media URLs. Record coarse operation types and sanitized errors.

If the car integration regresses playback after release, ship a corrective build with a higher version code that reverts the integration while preserving any compatible migrations. Keep the prior known-good artifact and a private backup; do not recover by uninstalling the owner app or assuming an older APK can read a newer database.

Public Play distribution is a later release activity: opt into Android Auto distribution, complete the applicable media-quality review, supply listing/testing material, and validate any custom-action feedback requested in review. Personal APK testing does not certify Play eligibility.

Required final deliverables:

- [ ] Feature commits with catalog, service, controls, capture and recovery tests.
- [ ] TDD receipt and CI results tied to the release source commit.
- [ ] `docs/android/android-auto.md` covering user setup, behavior and troubleshooting.
- [ ] `docs/android/evidence/android-auto-validation.md` recording each matrix result, versions, screenshots and outstanding device/host gaps.
- [ ] Signed APK and verified S25 upgrade without personal-data loss.
- [ ] Real-car test result, or an explicit pending milestone D with the reason.

The feature is fully verified only when discovery, browsing, resume, downloads, −10/+30, voice request handling and durable Distill all work through Android Auto, and existing phone playback remains intact. Host-dependent button placement and untested hardware remain documented limitations.
