# Existing online transcripts before local transcription

November 5, 2026. Implemented on `main`; dated validation is recorded below. This is a read-only lookup of transcripts that already exist, followed by local Whisper when no suitable result is available.

## Source order and controls

An explicit **Prepare transcript** action uses these sources:

1. A suitable publisher transcript linked from RSS, retaining the existing publisher behavior.
2. An existing transcript from PodcastTranscript.ai when **Settings → Transcript preparation → Look for existing online transcripts** is enabled.
3. Local Whisper with the selected model and language.

The setting defaults to off and stays on this installation. It is neither synced nor restored as portable consent. Opening a screen, refreshing a feed, or playing an episode does not trigger a lookup. **Transcribe locally again** bypasses publisher and service lookup. A local resume can check the service again, subject to the retry cache.

Lookup only applies to shows with public-source provenance: a directory follow or an explicit public-feed approval in Library sync. Merely having a URL that looks public is insufficient. Other manual/private feeds retain publisher/local processing. The public-source approval does not require enabling cloud sync for directory follows.

## Provider contract

Provider: [PodcastTranscript.ai's documented API](https://podcasttranscript.ai/api). Public reads require no API key. The integration uses only these endpoints:

- `GET https://backend.podcasttranscript.ai/api/v1/transcriptions?q=<episode title>&limit=20`
- `GET https://backend.podcasttranscript.ai/api/v1/transcriptions/<id>?include=segments`

The list envelope is `{ok:true,data:[...]}`. Detail is `{ok:true,data:{transcription:{...}}}`. The latter nesting was verified against a real public response. The detail's `segments` have `start` and `end` in seconds and a `text` field; the app converts these to integer milliseconds. Public verification fetched one list item, its detail and an exact-title search, without authentication or creating a job.

The app sends the episode title and its network address is visible to the service. It compares enclosure URLs locally; it does not submit the feed address, audio URL, audio bytes, listening position, saved moments, credentials or ChatGPT data. It never calls the provider's job-creation endpoints. No API-key configuration, subscription purchase or cloud transcription is introduced.

## Matching and bounded failure

Search titles nominate candidates; they never prove identity. A result must match the canonical current enclosure URL in `audio_url` or `original_url`, including query parameters. The detail ID and enclosure are verified again. No title-only or fuzzy adoption is allowed. Different enclosure URLs cause a safe miss, including CDN aliases that cannot be proved identical.

Known episode and provider durations must agree within two seconds. Results need finite ordered segment starts, positive bounded ranges, nonempty text and a final segment near the declared end. Plain text, zero-timed passages, previews, malformed data, oversized data and materially different durations fall back to local transcription. These checks cannot prove perfect timing for dynamically inserted ads; the UI makes that limitation explicit.

### Duration reference

The episode's stored duration is the reference for that two-second check and for publisher transcript bounds. Its precedence on a feed refresh (#152):

- An unchanged enclosure that has been played (here, or on the device whose backup was imported) keeps the stored value. Each playback checkpoint overwrites it with the player's measured duration when the player knows it, and local transcription overwrites it with the decoded length, so it describes the audio that actually plays rather than the feed's declaration, which differs for dynamically inserted ads.
- A changed enclosure takes its new declaration, or becomes unknown (zero) when the feed declares none, because a measurement of the earlier audio does not describe its replacement. Replacement audio served at the same URL is not detectable. In both cases the next playback measures it again.
- Otherwise the feed's declaration applies, and the last known value is kept when the feed omits it.

Consequently, after an episode has been played, a provider transcript must match the measured length, not the declared one, and publisher segments are bounded by the measured length. A publisher transcript that ends well before a longer measured length (inserted ads) is therefore saved as partial, which holds show automation and lets the next preparation use Whisper; previously the outcome depended on whether a refresh had run since playback. Never-played episodes behave as before, including a decoder measurement being replaced by the declaration on refresh. Known gaps, each corrected by the next playback of the new audio: a played episode whose player never reported a duration keeps its last declaration, and an enclosure that changes while the old audio is still loaded in the player can receive that audio's measured length from the next checkpoint. No schema or backup change is involved; an imported backup carries the stored value and last-played time unchanged. Local Whisper coverage uses each transcript revision's own decoded duration and is unaffected.

Bounds: one page of at most 20 candidates, at most three detail reads, a 15-second lookup deadline and 12-second per-call timeout. Responses are bounded to 2 MB, cached segment JSON to 1 million characters, with at most 30,000 passages and 24 hours of audio. Redirects are not followed. Requests participate in coroutine cancellation, including cancellation of the underlying HTTP call.

A missing result is cached for 24 hours. Service/parse/transport failures back off for 15 minutes; HTTP 429 honors numeric `Retry-After` bounded to one minute–24 hours. The cache is keyed to episode title and audio URL, so changed identity permits a new attempt. No service error prevents the Whisper fallback. Network policy is checked before lookup; downloaded audio can still transcribe locally offline or while service requests are disallowed.

## Storage, replacement and downstream use

Room schema 8 adds separate online content and lookup-cache tables plus per-attempt flags. The 7→8 migration is combined with the preexisting website/post-draft schema increment; there is one migration, with no destructive fallback. Online content records provider ID, source URL, retrieval/creation dates, language, duration, revision and a content hash.

Adoption is transactional. Before committing, check the active attempt, processing state, current enclosure/fingerprint, public provenance and current opt-in. Pause, cancellation, source changes or withdrawn consent reject late results. A successful lookup avoids audio/model downloads and local inference. Revoking lookup consent stops new service requests; already downloaded text remains available offline.

The reader, summary/chapters and episode chat use the selected online transcript. Existing online text remains readable and available to episode AI while an explicit local replacement is paused or fails. A successful local replacement becomes the selected source. Changing the enclosure invalidates the cached online selection.

Online segment times support reader/chapter navigation with a timing caveat. **Saved audio-window captures and automatic ad skipping require publisher or local coverage.** Saving a moment can therefore start local preparation even when a full-episode online transcript exists. Capture never opts into service lookup. Automatic recovery can resume a previously requested lookup, subject to the current setting and source/network checks. Both ad approval and the playback service reject online ad timing.

Manual Backup & Restore includes downloaded online text and provenance. It excludes retry cooldowns and service consent, clears pending lookup/replacement flags, and restores active jobs as paused. Older backup formats acquire safe defaults. Online JSON/timing/hash checks also run during import. Spark deployment continues to keep finished text local.

## Validation

Focused JVM tests cover the real response envelope, exact-URL identity and query differences, duration/timing/preview rejection, response bounds, opt-in/public-source/network gates, errors and retry caches, changed identity, cancellation and late results, chat, capture isolation, retained online text and backup round trips. A Room upgrade regression covers every published schema (1–7), retaining progress and saved content while defaulting service flags off. The device migration test also includes schema 7; it was compiled but not run on a device in this session.

Run from the repository root with JDK 17:

```sh
./gradlew :app:testDebugUnitTest --tests '*OnlineTranscript*'
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

**Results:** all 248 JVM tests pass (82 core, 166 app), including 16 focused online-lookup/migration tests. Debug lint, the app APK and instrumentation APK builds pass. Direct pipeline tests verify that a service hit needs no local model, a miss reaches the local model gate, and pause/resume preserves local replacement intent. See the [validation receipt and APK hashes](evidence/online-transcripts-validation.json). Device tests were compiled, not executed in this session. No owner-phone installation or published release is implied by these repository checks.

## S25 installation — November 5

Installed **0.10.1-alpha.1 (version code 19)** as an in-place USB update on the owner’s S25, using version overrides for the phone build (the same version is now recorded in `gradle.properties`). Release lint and assembly pass. The non-debuggable APK matches the installed signing certificate and Firebase client configuration, keeps paid content sync disabled, and passes 16 KB alignment checks. Private before/after exports verify that existing library tables, the queue, saved text and portable preferences are preserved; the new tables start empty. The app opens and the online-lookup setting is visible and off. No fixture/test APK or cloud transcription job was used on the phone. The signed artifact and private verification receipt are retained outside the repository. This is a phone installation, not a published release.
