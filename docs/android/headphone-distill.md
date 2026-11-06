# Distill from headphone controls

Available in [0.15.0-alpha.1](https://github.com/andrepaim/distillpod/releases/tag/v0.15.0-alpha.1).
Watch the [captioned demo](../demos/headphone-capture.mp4) or read its
[text walkthrough](../demos/README.md#hands-free-headphone-capture).
The recording simulates the media-button command; physical earbud mapping still
requires the device checks described below.

## Goal and scope

Save the preceding 60 seconds of the current DistillPod episode using a Liberty 4 NC gesture, without taking the phone out or interrupting playback. The earbuds recognize the gesture; Android delivers its assigned media command. DistillPod does not receive a raw triple-tap gesture or a reliable left/right earbud identity.

The first version remaps **Previous track** media-button events to Distill. It is optional and off by default. Play/pause, next, volume and noise-control gestures retain their existing behavior. Other accessories that send the same previous-track media-button event are affected while DistillPod handles media controls. Direct player controls in the phone UI and Android Auto are not remapped.

This feature requires no new account, server, permission, Bluetooth SDK or accessibility service. It does not listen to the microphone. The setting is device-local and is excluded from portable preferences and cloud synchronization.

## Setup and expected experience

1. In the Soundcore app, open **Liberty 4 NC → Controls** and assign the desired gesture (for example triple tap) to **Previous track**, if offered by the installed firmware/app. Remove other mappings to Previous track if you do not want those gestures to distill too. Single-earbud mode may have separate controls; verify those separately.
2. In DistillPod, open **Settings → Playback & headphones** and enable **Distill with previous-track button**.
3. Start an episode in DistillPod. Use the configured gesture while listening, including with the phone locked.
4. A successful new save plays the existing pen-scribble confirmation through the current media output at media volume. This means the moment was saved, not that AI generation finished. The sound can be disabled independently.
5. Find the moment in Saved. Transcript preparation and AI generation follow the existing account, download and processing rules. Saving still works without an available transcript or ChatGPT connection.

Turning the option off restores normal previous-track media-button behavior. The feature is intended for an existing playback session; it does not promise to start an app that Android has force-stopped or take control from another media app or an active call.

## Historical implementation record

The remaining sections preserve the implementation design, sequence and dated
validation. Their branch-specific instructions are not current setup steps.

### Research and implementation baseline

Reviewed 2026-11-05. Android app baseline: `origin/main` at `1ed76a3`.

During implementation, the sound dependency landed on main in `8fabf40`. The feature was integrated with that revision; its final diff against main contains only headphone capture, its settings, tests and documentation.

- [Soundcore Liberty 4 NC manual and control tutorial](https://service.soundcore.com/article-description/A3947-Liberty-4-NC-User-Manual): official model-specific setup reference. Exact triple-tap mapping and single-earbud behavior must be verified on the owner's firmware; Liberty 4 and Liberty 4 NC are different models.
- [Media3 MediaSession.Callback.onMediaButtonEvent](https://developer.android.com/reference/androidx/media3/session/MediaSession.Callback#onMediaButtonEvent(androidx.media3.session.MediaSession,androidx.media3.session.MediaSession.ControllerInfo,android.content.Intent)): a session can handle a media-button event and return true to prevent default player dispatch.
- [MediaSession media-key mappings](https://developer.android.com/reference/androidx/media3/session/MediaSession): previous/next media keys normally invoke the player's previous/next operations. Gesture counting should stay in the earbuds when their firmware already converts the gesture to a command.
- Repository uses Media3 **1.5.1**. Compile and test against the pinned dependency; no Media3 upgrade is part of this feature.
- [Pinned Media3 legacy event implementation](https://github.com/androidx/media/blob/1.5.1/libraries/session/src/main/java/androidx/media3/session/MediaSessionLegacyStub.java): legacy key events do not carry a computed trust flag, requiring Android platform verification in the custom handler.

## Design

### Settings and onboarding

Add `headphoneDistill` to `Preferences` and DataStore, default false. Add a Playback & headphones destination with the switch, a short explanation of the command tradeoff, Soundcore setup instructions and the existing confirmation-sound control. Keep the existing sound setting reachable from Import & notifications for compatibility.

### Event routing

Use `AutoLibraryCallback.onMediaButtonEvent`, the callback already owned by the service's MediaLibrarySession. Keep controller trust checks. Handle only valid ACTION_MEDIA_BUTTON intents carrying KEYCODE_MEDIA_PREVIOUS when enabled. Return false for unrelated or malformed events and let Media3 process them normally.

Media3 1.5.1 constructs legacy key-event caller information with `trusted=false`; verify otherwise-untrusted external key callers through Android's `MediaSessionManager.isTrustedForMediaControl` using their package and UID. Do not weaken the existing browser/controller connection policy. The pinned Media3 implementation forwards only key-down events to the callback; the filter also safely handles release events if delivered directly or by a later version. Existing notification-session command restrictions remain in place when remapping is disabled.

For a remapped press, consume down/up and repeat events. Capture once on the first ACTION_DOWN with repeatCount zero. Suppress duplicate delivery of that press and short repeat bursts with a monotonic debounce interval. Never pause or seek to implement gesture recognition. Retain consumption of a press's key-up even if the setting is disabled before release.

### Capture and lifecycle

Extract the current custom Distill command's save operation into one callback method and reuse it. Snapshot media ID, position and local source path on the player's application thread before starting asynchronous database work. Do not recalculate the position after waiting for storage. Reject a missing episode/zero position and prevent concurrent saves through the shared in-flight guard.

Commit through `CaptureRepository.save`, then dispatch `prepareSaved`. Keep the existing `momentId` result for Android Auto. A failed save must not trigger the success sound or fall through to previous-track playback. Show a brief failure message when the headphone capture cannot be saved. A committed save survives later preparation failure; ordinary startup recovery handles pending preparation.

### Confirmation sound dependency

Reuse the confirmation implementation on main at `8fabf40`, originally developed on `feat/distill-confirmation-sound` at `3fe6cc3`. It provides the owner-selected pen-scribble asset, independent sound setting, repository-level after-commit hook, and regression tests. All capture surfaces share that hook, so new saves sound once and repository-deduplicated requests, recovery and failed saves do not sound. Its original branch/worktree is not changed. Validate the combined result; historical sound tests alone do not validate this feature.

## Delivery steps

1. Create `feat/headphone-distill` in a separate worktree from fetched main; review and integrate the existing sound dependency.
2. Add this plan, persistent settings and setup UI.
3. Implement the media-button filter, shared capture method, service preference observation and failure feedback.
4. Add regression tests for routing, duplicate protection, settings persistence, immutable capture position, failure and recovery behavior. Add an Android test through the real service/media session using synthetic local audio and a platform media controller.
5. Run the full JVM suite, lint and debug APK build, repository Python tooling tests and targeted emulator acceptance where available. Review the combined diff and check whitespace/local documentation links.
6. Commit the reviewed feature branch. Feature delivery does not publish an APK, merge main, install on the owner's phone or run live-account tests. Those require their existing respective authorizations.

## Acceptance criteria

- A new installation leaves existing headphone behavior unchanged.
- The enabled setting persists across app/service restarts and changes without restarting playback.
- One configured gesture saves exactly one moment with the original episode and position, including if playback moves while the database request is pending.
- Playback continues; the queue and current episode do not change as a result of a remapped previous key.
- Key-up, long-press repeats and duplicate delivery cannot produce extra moments or an accidental previous-track action.
- Play/pause and next media keys remain delegated; direct Android Auto/phone controls retain their behavior.
- Invalid capture and storage failures produce no success sound and do not skip the episode. Later captures can succeed.
- Transcript/account unavailability preserves the moment for later processing.
- Confirmation honors its independent preference and never changes system volume or requests audio focus.

## Validation matrix

| Layer | Coverage |
| --- | --- |
| JVM / Robolectric | Event allowlist, trust boundary, default-off behavior, down/up/repeats, debounce, setting changes during a press, malformed intents, persistent preferences, real callback snapshot/save path and failure retry |
| Existing regression suite | Capture transaction ordering and deduplication, sound after commit, preparation/recovery, Android Auto session commands, playback/transcription behavior |
| Android emulator | Real PlaybackService and platform media-key dispatch; synthetic local episode; current episode/queue preserved, one saved moment and settings changes |
| Physical phone + Liberty 4 NC | Actual triple-tap command, screen-locked operation, audible confirmation over Bluetooth, one/both earbuds, reconnect and multipoint/call behavior |

Physical acceptance should record phone OS, earbud firmware, Soundcore version, app revision, gesture mapping and results. Use a known public episode and avoid changing the owner's data or account state. Emulator key injection does not prove the earbuds emit that key or that Bluetooth sound routing works.

## Evidence

Validated implementation: `5622ceb`, based on main `8fabf40`, on 2026-11-05. The feature tree was byte-identical before and after rebasing onto the updated main (`accab9318eb13c2494dc3722cd347bd249d701e1`). This receipt is a documentation-only follow-up.

| Check | Result |
| --- | --- |
| `:core:test` | 91 tests passed |
| `:app:testDebugUnitTest` | 272 tests passed, including 12 new headphone tests |
| `:app:lintDebug` | Passed |
| `:app:assembleDebug` and `:app:assembleDebugAndroidTest` | Passed |
| `python3 -m unittest discover -s scripts -p 'test_*.py'` | 15 tests passed |
| `HeadphoneDistillDeviceTest` | Passed on an isolated Windows-hosted Android 16 / API 36 x86_64 emulator |
| `DistillSoundDeviceTest` | Passed on the same emulator |
| Documentation links and `git diff --check` | Passed |

The headphone device test uses both a platform media controller and Android's system media-key route. It verifies repeat suppression, original capture position, preserved two-episode playback, screen-off capture, unchanged direct modern skip controls and pause handling, and disabling capture without restarting the service. It uses silent local WAVs and complete synthetic transcript coverage, with no AI or account requests. The sound test separately verifies native confirmation playback after a committed save; it does not establish perceived loudness or Bluetooth routing.

Reproduce the JVM/build checks with the commands in [development](../development.md). For the targeted emulator checks, install both debug APKs on a verified disposable emulator, then run:

```sh
adb -s emulator-5560 shell am instrument -w -r \
  -e class dev.distillpod.android.HeadphoneDistillDeviceTest,dev.distillpod.android.DistillSoundDeviceTest \
  dev.distillpod.android.test/dev.distillpod.android.IsolatedTestRunner
```

The completed instrumentation run reported **OK (2 tests)**. Match the serial to your disposable emulator; the headphone test refuses physical hardware. No owner-phone installation, physical-earbud check, live-account action, APK publication or remote CI run was performed. Physical Liberty 4 NC triple-tap mapping, one-earbud/multipoint behavior and Bluetooth confirmation sound remain unvalidated.
