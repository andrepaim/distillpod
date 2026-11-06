# Headphone voice shortcut

Voice-enabled builds support an optional **Next track** media-key shortcut. This change is developed on `feat/headphone-voice-next`, based on current main with the existing voice branch integrated as a dependency. Default builds still disable voice.

## Set up

1. Save your OpenAI API key in **Settings → Voice**.
2. Open **Settings → Playback & headphones** and enable **Next track → Ask about the podcast**. Grant microphone and notification access when requested. Both headphone shortcuts are configured here; Voice settings manages the API key and answer preferences.
3. In the headphone app, assign the desired gesture to **Next track**. For Liberty 4 NC, configure it under Controls. The headphones determine the gesture; DistillPod receives the resulting media key.
4. Start an episode with DistillPod open. Wait for the **Headphone voice ready** notification before locking the phone.

The Next card shows Off, Setup required, Ready or Open app to prepare, with a setup/permission action when needed. **Headphone setup** expands the gesture, single-earbud and locked-phone instructions. **Feedback → Moment saved sound** controls confirmation sound for headphone, phone and car captures. Existing choices are retained when upgrading; the preference keys and defaults are unchanged.

Next pauses the episode and starts a question. Wait for the tone, then speak. End-of-speech detection finishes the question automatically; another Next press finishes it explicitly. During preparation or the answer, Next cancels/stops voice and resumes the unchanged podcast when its playback lease remains valid. Presses during cleanup are consumed. Duplicate, repeated and rapidly repeated events cannot start duplicate questions.

Previous retains its separately configured Distill shortcut. Turning off the Next mapping restores ordinary next-track behavior. The mapping intercepts hardware media-key events, not normal player UI commands. Other accessories sending the same Next media key also trigger the shortcut while DistillPod handles playback. Untrusted controllers cannot invoke it.

## Microphone and background behavior

A microphone foreground service is prepared while the Activity is resumed. Its persistent notification keeps the shortcut ready when the screen turns off; arming the service does not open an AudioRecord or contact OpenAI. The service stops after five minutes without playback or voice, when the setting/key is removed, or when the process is stopped. It does not restart itself after process death. Reopen DistillPod to prepare it again. A Next event never attempts to create an ineligible microphone service from the background; it shows a local setup notification instead.

This follows Android's [microphone foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start). A bounded partial wake lock is held only during the headphone interaction, not while the service waits idle. Notification actions expose Done, Cancel and resume, and Turn off shortcut. Notifications contain phase/setup information, not the answer transcript or credential.

Bluetooth headset voice requires Android 12 or later. The app uses AudioManager's [communication-device API](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)), waits for the selected route, and chooses its matching headset input. Wired/USB headsets are also supported. Recording checks the actual routed input before forwarding each PCM chunk; it never silently substitutes the handset microphone. The communication route is held for the spoken answer, then cleared before normal podcast resume. Headset disconnects leave playback paused. Calls/audio-focus loss cancel voice without resuming. These paths require physical-headset validation, including vendor routing behavior.

When Android Auto is connected, Next uses the bound car voice screen. A legacy/unsupported car host or unknown connection state never falls back to the phone/headset microphone. Phone/car screen lifecycle cleanup cancels only voice sessions owned by that interface; it does not terminate a headphone session when the Activity stops.

The existing local key vault, transcript boundaries, one-question limits and provider connection remain unchanged. Readiness is re-evaluated on key changes without storing key material in settings, backups or sync. The headphone preference is local and defaults off.

## Validation

Source revision: `23a9ffa`, validated November 6, 2026.

- **412 JVM/Robolectric tests passed:** 91 core and 321 app, with no failures, errors or skips.
- Debug lint, enabled app/test APK assembly and default-disabled app assembly passed.
- All **19 Python repository/release-tool tests** passed.
- **2 instrumentation tests passed** on the disposable `DistillPod_Auto_Test` emulator, API 36, isolated ADB server and read-only AVD session. Next invoked voice while the screen stayed off, kept the selected episode/position, resumed after a local missing-transcript failure, and left no active recording while ready. Disabling the setting stopped the ready service; ordinary modern transport commands remained usable. The existing Previous shortcut also passed its real-service/system-key capture checks.

The emulator caught an initial Media3 controller transition reporting no item/position change, which incorrectly revoked the voice playback lease. The guard now ignores that no-op event while still canceling on seeks, episode changes and actual position jumps. Regression tests cover both paths. Legacy skip-command visibility remains controlled by the existing Media3 notification configuration; an off shortcut delegates to that existing behavior.

The device fixture deliberately has no transcript, so it fails before microphone routing or a provider connection. This validates the foreground-service lifecycle and key dispatch, not a successful recording or paid answer. Physical Liberty 4 NC/Samsung microphone routing, gesture delivery, disconnects, calls and a live question must be tested separately. No owner-phone installation or live provider request was performed. The test emulator was shut down after validation. Full CI and actual-car checks were not run for this branch.
