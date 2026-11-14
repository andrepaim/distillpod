# Realtime voice preview

The 0.16.0-alpha.1 signed personal-alpha release candidate includes this preview. Ordinary source/debug builds still default to disabled; use `-PdistillpodVoiceEnabled=true` to build it deliberately. See the [release notes](../releases/current.md) for setup and acceptance limits.

The `feat/realtime-voice` implementation adds an opt-in, one-question voice interaction using `gpt-realtime-2.1-mini`. Default builds keep the feature and car service disabled. Voice uses an OpenAI project API key entered by the owner on the phone. Existing ChatGPT OAuth text features remain separately authorized.

## Build and setup

```sh
./gradlew :app:assembleDebug -PdistillpodVoiceEnabled=true
```

Voice needs no Firebase configuration, Google sign-in, broker endpoint or backend deployment. Existing optional library-sync configuration is independent.

Open **Settings → Voice → Add API key**, paste a key from the API organization where you claimed your Pro credits, and choose **Save key**. The password field is masked and its dialog prevents screenshots. Its draft is not saved across activity recreation. **Replace API key** opens an empty field; the saved key is never displayed. **Remove API key** cancels voice and removes the stored credential. Replacing a key also cancels an active voice session.

The key is encrypted using the existing AES-GCM `TokenVault`, with its own app-private preference namespace and Android Keystore alias. It does not enter the APK, source files, portable settings, library backups or sync. App-level Android backup is disabled. Reinstalling or clearing app data requires entering it again. Encryption does not protect an API key from a compromised device while the app uses it; this owner-provided credential flow is intended for the personal alpha.

Choose **Ask** in the player (TalkBack: "Ask about this"), review the disclosure and grant microphone access. The phone connects directly to OpenAI's Realtime WebSocket endpoint with the saved key as bearer authentication. API usage draws on the associated project's available credits/balance. Saving validates key syntax locally and does not make a paid request; provider entitlement is checked only when asking. No live access or billing claim is established by local fixture tests.

Voice-enabled builds provide the optional **Next track → Ask about the podcast** setting under **Settings → Playback & headphones**, alongside Previous-track capture. Voice settings contains the key, answer preferences, a link to headphone controls and Android Auto setup help. Prepare headphone voice with the app open before locking the phone; its notification reports readiness. See the [headphone voice shortcut guide](headphone-voice.md) for Next/Previous behavior, microphone routing and validation limits.

## Transcript context

A matching local or publisher transcript must cover the listening moment; uncertain online timings are excluded. Voice receives **all available valid transcript segments ending at or before that position**, in chronological order. There is no two-minute window or local transcript-character cutoff. A segment crossing the position is excluded because its text can contain unheard speech. Partial transcripts remain usable when they cover the selected moment; missing speech is not reconstructed.

The transcript is sent as acknowledged source-data messages, separately from the assistant's instructions, before microphone readiness. Transport chunks preserve the full text and Unicode; they do not select or summarize passages. Provider context truncation is disabled. If the provider rejects the complete context as too long, voice explains the limit and suggests an earlier listening position instead of silently dropping the beginning. The retained `gpt-realtime-2.1-mini` model has a finite context window, and more transcript text can increase input-credit usage and preparation time. The model may still use two bounded keyword searches to locate earlier passages, but these are not its only access to earlier material.

Answers may use general knowledge for explanations, examples, comparisons and reasoning without routine transcript disclaimers. Claims about what a speaker said, quotes and episode timestamps must remain supported by transcript evidence; missing evidence or uncertainty is mentioned when it materially affects the question. Source text remains untrusted data, and later episode content must not be invented. Spoken explanations are not independently verified and are not the text chat's validated JSON/citation output. See [full-context design and validation](voice-full-context.md) for evidence boundaries and prompt evaluation scenarios.

## Android Auto feasibility

The enabled preview declares the templated media category and Car API 8. It presents Ask when a phone key is configured, Test microphone and Cancel and resume on a dedicated voice screen. Key entry stays in Settings → Voice on the phone. Google documents restrictions on templated media distribution; this is a personal-alpha prototype, not a claim of production Play eligibility.

Test microphone records approximately three seconds and plays the recording back. This tests microphone/speaker routing without an API request or saved recording. Real Ask streams the question and selected transcript text directly to OpenAI. No voice application server is involved.

Use Desktop Head Unit and a parked physical car to check the car screen, actual microphone source, USB/wireless modes, locked phone, permission rejection, cancellation, calls, navigation, disconnect and repeated sessions. A phone build or unit test cannot establish car compatibility. Host validation allows test hosts only in debuggable builds; release builds require privileged trusted hosts.

## Playback and resource ownership

A single coordinator snapshots the actual player and pauses it before acquiring speech audio focus. Duplicate taps are ignored. Manual phone controls and external media commands cancel automatic restoration; player transitions and seeks also revoke the lease. Normal completion resumes only if the episode was previously requested to play and is still unchanged. Interrupted calls/focus loss and car disconnect leave playback paused. Phone voice stops when its player screen leaves the foreground. Recording never restarts after process death.

The input closes before the answer starts. A 16-to-24 kHz streaming converter adapts car PCM. Output is bounded to 45 seconds and waits for the hardware playback head before normal resume. Socket events and outgoing audio have bounded buffers. Startup, speech, generation and session timeouts close resources. Session duration, first-audio timing from connection start and reported token counts are available in `RealtimeVoice.usage` in memory; these are not exported or uploaded. Answer/error text is also kept only in memory.

Exact-position resume is the default, with an optional local three-second rewind. Another tap starts a fresh question. Persistent voice history, continuous follow-ups and barge-in are deferred. A local ready cue finishes before capture starts. Failures display a short phone/car message; no automatic paid retries occur.

## Validation and limitations

Local tests cover duplicate taps, cleanup, cancellation, missing context, startup failures, transcript boundaries and conversion. A fake WebSocket checks audio configuration, bearer authentication with a fixture project key, answer playback, transcript and drain without calling OpenAI. Key-store tests cover saving, validation, replacement preservation, removal and sanitized vault errors. A separate instrumentation test uses an isolated fixture vault to exercise Android Keystore encryption, reopening and removal; it never reads an owner's credential.

Physical-car microphone routing, Android Keystore instrumentation, live model quality, latency and real API-credit consumption need deliberate validation. The initial implementation had no attached device; subsequent phone reports and DHU checks are separate evidence, recorded below. Keep ordinary source/debug builds disabled by default. The 0.16.0 personal-alpha distribution explicitly enables the preview; physical acceptance limits still apply. Private phone previews use the retained signing identity for in-place upgrades; publication follows the [release checklist](../releases.md).

The initial implementation was based on `origin/main` commit `95c28aa`. Its broker was subsequently removed in favor of this owner-provided phone key. Historical broker tests do not validate the current credential flow.

The phone-key change passed 91 core and 285 app JVM/Robolectric tests, debug lint, app and instrumentation APK assembly, and 15 release-tool tests on November 6, 2026. Voice-enabled builds require only `distillpodVoiceEnabled=true`; a default-disabled APK also assembled successfully. The fixture Keystore instrumentation test was compiled but not executed because no device/emulator was available. No real API key or live provider request was used.

### Answer-burst regression

The owner's first S22 phone trial captured a question, played an answer and resumed the podcast, but displayed the generic unavailable message. A fake-provider regression reproduced a matching failure: 200 audio chunks sent ahead of slower playback exceeded the old 64-event queue. The receive queue now bounds queued payload characters (4 MiB), individual message size and total event count, accommodating a short spoken answer without discarding ordinary bursts. The regression verifies all 200 chunks play and final output drains. Separate tests retain overflow protection and verify budget recovery after consumption. An actual interrupted answer now has an answer-specific message instead of suggesting a credential or transcript setup failure. The fix requires a repeated physical-phone trial; the owner's first result is not proof of the patched behavior.


### Phone voice interface

The player offers **Ask** and, after an interaction, **View last answer**. API key management and the optional three-second rewind are in **Settings → Voice**. A first Ask without a key opens those settings. Key encryption and namespaces are unchanged, so an existing phone key is retained.

Asking opens a modal sheet over the paused player. Preparing shows a progress indicator and a reminder to wait for the tone. Listening shows a timer and a waveform computed from the actual PCM microphone RMS; silence produces a quiet baseline. **Done** finishes the question explicitly; automatic end-of-speech still works. The connection closes microphone input before switching to Answering. Manual completion disables VAD and processes its ordered acknowledgement before committing, avoiding duplicate commits when VAD already completed the same utterance.

The spoken answer appears in a scrolling area capped at 220 dp inside the sheet. New text follows the bottom until the user drags the transcript. The sheet closes after a short return-to-player transition following successful cleanup. Last-answer review opens only on request and never starts another API call. Errors and retry controls stay in the sheet; dismissing it leaves no inline transcript or red error block on the player. The most recent answer remains process-local, with no persistent conversation history. Android Auto keeps native phase labels and large Done/Cancel actions; microphone-level updates do not invalidate its screen.

The sheet update passed 91 core and 297 app JVM/Robolectric tests, debug lint, app/test APK assembly and 15 release-tool tests. Focused coverage includes real PCM meter levels, bounded transcript layout, completed-sheet dismissal, last-answer review, error dismissal, explicit Done and the VAD/Done commit race. These are automated checks; a repeated live phone/car interaction is still required to validate the revised interface on hardware.

### Android Auto entry-point correction

The owner reported that preview 3 showed the ordinary car podcast player with no Ask button. The original code exposed Ask only inside an otherwise unlinked Car App Library pane; the regular MediaSession layout never advertised the command. That was an implementation gap, not established car support.

The corrected enabled build advertises **Ask about this** in the regular car media player's extra/overflow actions, including the session's initial legacy layout. Phone notification controls retain their existing actions. A supported host uses the **DistillPod** launcher entry for a native `MediaPlaybackTemplate`, an **Ask** header action and a bounded local Library list. A separate Voice launcher icon is not guaranteed. The root registers the same Media3 player's platform token with `MediaPlaybackManager` before rendering; token retrieval is restricted to controllers belonging to this application.

The build includes `app-projected`, which supplies the `ProjectedCarAudioRecord` class selected by `CarAudioRecord.create()` on Android Auto. The earlier core-only dependency did not package that implementation. Ask from ordinary media controls asks an already host-bound Car App Library session to bring the voice screen forward. If no such host session is bound, the command returns an explicit unsupported error; it never substitutes the phone microphone. Open **DistillPod** from the car launcher first. **Settings → Voice → Android Auto setup** reports whether a car voice screen is connected.

Visibility of the media action, support for the templated media category and actual car microphone capture remain distinct checks. A host can render the standard player without supporting the voice screen. Do not claim hardware compatibility from callback or template-model tests. After upgrading, reconnect Android Auto and check the expanded player's overflow as well as the car launcher. Key entry stays on the phone and the existing key namespace is retained.


The entry-point correction passed 91 core and 308 app JVM/Robolectric tests, debug lint and app/test APK assembly. Tests cover enabled/disabled media-action exposure, Ask dispatch, unavailable-host behavior, internal-only token retrieval, host-session replacement, the native Ask/Library header and packaging of the projected microphone class. These checks do not validate car-host discovery, head-unit rendering or real microphone capture. Preview 4 (private version code 28) is prepared for that physical test.

### Desktop Head Unit follow-up

On November 6, 2026, the connected S25 Ultra running Android Auto 17.9 successfully opened preview 4's templated player in Google's Windows Desktop Head Unit 2.0. The launcher showed a single DistillPod entry, with its longer service label truncated. Opening it rendered native **Ask** and **Library** header controls. The Library action and reopening through the playback shortcut also worked. Host negotiation reported Car API 8. This establishes discovery and rendering on that host, not microphone capture or real-vehicle compatibility.

The standard player's tab highlight occupied an empty icon slot above the label: the root browse items supplied no icons. Source revision `5ac5421` adds distinct monochrome resource icons for all four tabs and shortens the first label to **Continue**. A temporary default-disabled build of that revision was installed with replacement to exercise the standard browser in DHU. Its Continue and Queue selections rendered with icons inside their highlights. The final voice-enabled preview, `0.15.0-voice-preview.5` (private version code 29), was then installed with replacement and its native Ask/Library player rendered successfully after reconnecting DHU. The phone's original installation date was retained. The same revision passed all **308 app JVM/Robolectric tests**, debug lint and enabled/disabled APK assembly. Its signing certificate matches the retained owner identity. No release metadata was changed and no live provider request was made.

The settings instructions, service label and unsupported-host message now refer to **DistillPod** in the car launcher. A separate Voice entry is not required. The cause of the owner's real-car fallback to the older media player remains unconfirmed; reconnect and open the launcher entry before comparing with the DHU result. Actual car microphone capture and a new paid question remain owner acceptance checks. Private captures and the APK receipt are outside the repository at the path recorded in `/tmp/distillpod-voice-dhu-dir`. DHU and its ADB forward were left open for the owner's interactive test.
