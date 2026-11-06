# Realtime voice preview

The `feat/realtime-voice` implementation adds an opt-in, one-question voice interaction using `gpt-realtime-2.1-mini`. Default builds keep the feature and car service disabled. Voice uses an OpenAI project API key entered by the owner on the phone. Existing ChatGPT OAuth text features remain separately authorized.

## Build and setup

```sh
./gradlew :app:assembleDebug -PdistillpodVoiceEnabled=true
```

Voice needs no Firebase configuration, Google sign-in, broker endpoint or backend deployment. Existing optional library-sync configuration is independent.

On the phone player choose **Add OpenAI API key**, paste a key from the API organization where you claimed your Pro credits, and choose **Save key**. The password field is masked and its dialog prevents screenshots. Its draft is not saved across activity recreation. **Replace OpenAI API key** opens an empty field; the saved key is never displayed. **Remove API key** cancels voice and removes the stored credential. Replacing a key also cancels an active voice session.

The key is encrypted using the existing AES-GCM `TokenVault`, with its own app-private preference namespace and Android Keystore alias. It does not enter the APK, source files, portable settings, library backups or sync. App-level Android backup is disabled. Reinstalling or clearing app data requires entering it again. Encryption does not protect an API key from a compromised device while the app uses it; this owner-provided credential flow is intended for the personal alpha.

Choose **Ask about this**, review the disclosure and grant microphone access. The phone connects directly to OpenAI's Realtime WebSocket endpoint with the saved key as bearer authentication. API usage draws on the associated project's available credits/balance. Saving validates key syntax locally and does not make a paid request; provider entitlement is checked only when asking. No live access or billing claim is established by local fixture tests.

## Transcript context

A matching local or publisher transcript must cover the listening moment; uncertain online timings are excluded. Context is bounded to two preceding minutes, with no boundary-straddling future segments. The model may search earlier segments of the same transcript, at most twice, with bounded results. Excerpts are untrusted data. Spoken explanations are not independently verified and are not the text chat's validated JSON/citation output.

## Android Auto feasibility

The enabled preview declares the templated media category and Car API 8. It presents Ask when a phone key is configured, Test microphone and Cancel and resume on a dedicated voice screen. Key entry stays on the phone. Google documents restrictions on templated media distribution; this is a personal-alpha prototype, not a claim of production Play eligibility.

Test microphone records approximately three seconds and plays the recording back. This tests microphone/speaker routing without an API request or saved recording. Real Ask streams the question and selected transcript text directly to OpenAI. No voice application server is involved.

Use Desktop Head Unit and a parked physical car to check the car screen, actual microphone source, USB/wireless modes, locked phone, permission rejection, cancellation, calls, navigation, disconnect and repeated sessions. A phone build or unit test cannot establish car compatibility. Host validation allows test hosts only in debuggable builds; release builds require privileged trusted hosts.

## Playback and resource ownership

A single coordinator snapshots the actual player and pauses it before acquiring speech audio focus. Duplicate taps are ignored. Manual phone controls and external media commands cancel automatic restoration; player transitions and seeks also revoke the lease. Normal completion resumes only if the episode was previously requested to play and is still unchanged. Interrupted calls/focus loss and car disconnect leave playback paused. Phone voice stops when its player screen leaves the foreground. Recording never restarts after process death.

The input closes before the answer starts. A 16-to-24 kHz streaming converter adapts car PCM. Output is bounded to 45 seconds and waits for the hardware playback head before normal resume. Socket events and outgoing audio have bounded buffers. Startup, speech, generation and session timeouts close resources. Session duration, first-audio timing from connection start and reported token counts are available in `RealtimeVoice.usage` in memory; these are not exported or uploaded. Answer/error text is also kept only in memory.

Exact-position resume is the default, with an optional local three-second rewind. Another tap starts a fresh question. Persistent voice history, continuous follow-ups and barge-in are deferred. A local ready cue finishes before capture starts. Failures display a short phone/car message; no automatic paid retries occur.

## Validation and limitations

Local tests cover duplicate taps, cleanup, cancellation, missing context, startup failures, transcript boundaries and conversion. A fake WebSocket checks audio configuration, bearer authentication with a fixture project key, answer playback, transcript and drain without calling OpenAI. Key-store tests cover saving, validation, replacement preservation, removal and sanitized vault errors. A separate instrumentation test uses an isolated fixture vault to exercise Android Keystore encryption, reopening and removal; it never reads an owner's credential.

Physical phone/car, Android Keystore instrumentation, DHU, live model quality, latency and real API-credit consumption still need deliberate validation. The implementation environment has no attached device or installed emulator executable. Keep the feature flag disabled until those gates pass. The local debug APK is not a signed release update. Publication follows the [release checklist](../releases.md).

The initial implementation was based on `origin/main` commit `95c28aa`. Its broker was subsequently removed in favor of this owner-provided phone key. Historical broker tests do not validate the current credential flow.

The phone-key change passed 91 core and 285 app JVM/Robolectric tests, debug lint, app and instrumentation APK assembly, and 15 release-tool tests on November 6, 2026. Voice-enabled builds require only `distillpodVoiceEnabled=true`; a default-disabled APK also assembled successfully. The fixture Keystore instrumentation test was compiled but not executed because no device/emulator was available. No real API key or live provider request was used.
