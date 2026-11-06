# Realtime voice preview

The `feat/realtime-voice` implementation adds an opt-in, one-question voice interaction using `gpt-realtime-2.1-mini`. Default builds keep the feature and car service disabled. Existing ChatGPT OAuth text features remain separately authorized. Voice requires an API project funded by claimed API credits or an API balance.

## Build and setup

Build with the existing private Firebase Android configuration and a deployed credential broker:

```sh
./gradlew :app:assembleDebug \
  -PdistillpodVoiceEnabled=true \
  -PdistillpodVoiceBroker=https://YOUR-SERVICE/session \
  -PdistillpodFirebaseConfig=/PRIVATE/PATH/google-services.json
```

Only the HTTPS endpoint and public Firebase client configuration enter the APK. Never put an OpenAI project key in Gradle properties or resources. Normal builds need none of these settings.

On the phone player choose **Connect voice access**. This uses a separate Firebase Auth instance and does not bind or upload the library. The configured Firebase project must permit Google sign-in and the private build's Android signing certificate. Choose **Ask about this**, review the disclosure and grant microphone access. The configured broker permits only its designated Firebase UID. Disconnect voice access on the same screen.

A matching local or publisher transcript must cover the listening moment; uncertain online timings are excluded. Context is bounded to two preceding minutes, with no boundary-straddling future segments. The model may search earlier segments of the same transcript, at most twice, with bounded results. Excerpts are untrusted data. Spoken explanations are not independently verified and are not the text chat's validated JSON/citation output.

## Android Auto feasibility

The enabled preview declares the templated media category and Car API 8. It presents Ask, Test microphone and Cancel and resume on a dedicated voice screen. Google documents restrictions on templated media distribution; this is a personal-alpha prototype, not a claim of production Play eligibility.

Test microphone records approximately three seconds and plays the recording back. This deliberately tests that the actual car microphone and speakers are routed correctly without making an API request. No diagnostic recording is saved. Its audio stays in process memory. Real Ask streams question audio and selected transcript text directly to OpenAI; the broker receives identity and token requests, not podcast text or microphone audio.

Use Desktop Head Unit and a parked physical car to check the car screen, actual microphone source, USB/wireless modes, locked phone, permission rejection, cancellation, calls, navigation, disconnect and repeated sessions. A phone build or unit test cannot establish car compatibility. Host validation allows test hosts only in debuggable builds; release builds require privileged trusted hosts.

## Playback and resource ownership

A single coordinator snapshots the actual player and pauses it before acquiring speech audio focus. Duplicate taps are ignored. Manual phone controls and external media commands cancel automatic restoration; player transitions and seeks also revoke the lease. Normal completion resumes only if the episode was previously requested to play and is still unchanged. Interrupted calls/focus loss and car disconnect leave playback paused. Phone voice stops when its player screen leaves the foreground. Recording never restarts after process death.

The input is closed before the answer starts. A 16-to-24 kHz streaming converter adapts car PCM. Output is bounded to 45 seconds and waits for the hardware playback head before normal resume. Socket events and outgoing audio have bounded buffers. Startup, speech, generation and session timeouts close resources. Content-free session duration, first-audio timing from connection start and reported token counts are available in `RealtimeVoice.usage` in memory for diagnostics; these are not exported or uploaded. Latency from end of question still needs separate live evaluation. The app keeps the most recent answer/error in memory only; it does not add voice messages to backups or library sync.

## Credential service

See [broker setup](../../voice-broker/README.md). Code and container preparation do not deploy a service or activate paid infrastructure. The owner must provision the private API key, Firebase project and permitted UID before live testing. Backend deployment, owner-phone installation and APK publication follow their existing separate authorization requirements.

## Validation and limitations

Local Kotlin tests cover duplicate taps, playback cleanup, cancellation, missing context, startup failures, transcript boundaries and streaming conversion. A local fake WebSocket exercises configuration, captured audio, spoken output, final transcript and drain without calling OpenAI. Broker tests cover authorization, disabled state, request validation, issuance limits, expiry configuration and sanitized failures. CI runs the broker policy tests alongside existing checks.

Physical car/phone, Desktop Head Unit, live model quality, latency and API-credit consumption still require deliberate validation. The implementation environment had no attached device or installed emulator executable. Runtime transport credentials and actual provider/model entitlement have not been probed. Keep the flag disabled until those gates pass.

Current preview scope: exact-position resume by default, with an optional local three-second rewind; another tap starts a fresh question. Persistent voice history, continuous follow-ups and barge-in are deferred. A local ready cue finishes before capture starts. Failures display a short phone/car message; no automatic paid retries occur. Android media templates and microphone behavior remain hardware-validation gates.

### Local implementation receipt

Implementation starts from `origin/main` commit `95c28aa` in the dedicated `/tmp/distillpod-realtime-voice` worktree. Local validation on November 6, 2026 passed 91 core JVM tests, 280 Android JVM/Robolectric tests (including eight voice tests), debug lint and debug assembly. Both explicitly enabled and default-disabled builds were assembled. Fifteen repository release-tool tests and five broker policy tests passed. The Auth-only deployed dependency set audited with zero known vulnerabilities using `--omit=optional --omit=dev`.

The broker has not been deployed. No real API requests, owner-phone installation, emulator acceptance, DHU session or physical-car checks were performed. The local APK is a debug artifact, not a signed release update. Remote CI and the full car/live-provider gates remain required before integration/release claims.
