# Privacy and data handling

DistillPod keeps its library, queue, listening position, transcripts, saved excerpts and generated text in its private Android storage. ChatGPT credentials are encrypted with a key held by Android Keystore. Optional Firebase sign-in uses Firebase's SDK-managed app-private credential storage. Android application backup is disabled; neither provider's credentials enter library exports or library sync. This is a personal-alpha implementation; the repository's validation reports are not an independent security audit.

## Current Spark-only deployment

The selected deployment has billing disabled. Sync is optional and requires a Firebase-configured build; ordinary source/debug builds have no production Firebase configuration. Google sign-in and feed review are required before library data is shared. Queue and portable preferences can synchronize through Firestore without enabling Cloud Storage. Transcripts and other finished text remain local; their experimental Storage adapter is disabled in builds. Manual Backup & Restore remains available for text transfer.

## Optional library sync

Optional sync on `main` uses Google sign-in and Firestore storage for approved public subscriptions, listening progress, completion and saved moments. Cloud records include public feed/media addresses, feed-scoped episode identity and minimal display metadata, session/sequence identifiers, original positions/intervals, saved excerpts and completed moment quotes/insights with their model/source provenance. Record versions, operation receipts and ordered changes support retries and conflict review. The optional **Sync queue & preferences** scope adds queue and portable model/language/AI-model preferences. Full transcripts, episode analyses, conversation branches and explanations remain local: the experimental Cloud Storage adapter is disabled. Full RSS catalogues are refreshed from their source. Downloaded audio, models, unfinished computation and device policies remain local. Local job states, attempts and private file paths never enter sync.

On first connection, review which existing feeds to share. Existing and manually added sources are not uploaded automatically, because private RSS access tokens can be embedded in URL paths. Directory-result follows are treated as public sources. Password-bearing or query-bearing feed/media addresses are excluded even when the source is selected. Oversized records stay local. Feed-sharing choices are local and must be reviewed again after a manual library restore; cloud-restored subscriptions retain the account's prior sharing decision.

Firebase Authentication receives the Google identity used to sign in. The library stays bound to its original Firebase user ID. A different account cannot adopt its outbox or upload its retained local data. Signing out pauses cloud work and keeps the offline library. Multiple local account profiles are not implemented yet. Unfollowing creates a cloud deletion marker while preserving local episode data; this does not erase historical cloud changes. Complete account erasure and independent dated cloud recovery are later milestones.

Cloud records are protected by per-user security rules and the provider's transport/storage encryption. This is not end-to-end encryption; the project administrator can access stored data. The selected Firestore database is in São Paulo. The inactive Storage experiment is not part of the enabled sync scope. Sync uses bounded work and retries, with foreground/network opportunities and periodic recovery. Android idle and force-stop can defer work; reopening the app resumes it.

## Telemetry status

Optional cloud diagnostics use Sentry with a device-local, default-off consent setting, bounded offline storage and allowlisted technical events. See [cloud diagnostics](android/cloud-diagnostics.md) for collected fields, delivery limits and validation evidence. Firebase Analytics, Crashlytics and Performance collection remain disabled. Enabling library sync does not opt into diagnostics. Configured S22 log and handled-error delivery have been verified; native crash reporting is not included.

## Network requests

Podcast directory searches send the query to Apple’s iTunes podcast directory. Following/refreshing a feed, loading its artwork and streaming/downloading an episode contact the corresponding hosts. Model downloads contact their configured upstream host. These services can observe the requests made to them.

Transcript preparation starts automatically when an episode plays unless **Settings → Transcript preparation → Transcribe when playing** is disabled. It can fetch publisher transcripts and download episode audio under the existing network and power policies. Model downloads remain explicit. This device-specific setting does not enable online transcript lookup or upload audio for transcription. Disabling it prevents new automatic requests; already queued work can be paused through the transcript controls.

Optional existing-transcript lookup sends the episode title to PodcastTranscript.ai for approved public sources. It defaults to off, never submits audio or creates a cloud transcription job, and does not share the feed/enclosure URL, listening progress, saved moments or credentials. The provider can observe the query and network address. Already downloaded text remains readable when lookup is disabled. Consent and lookup cooldowns stay on this installation. See [the lookup design](android/online-transcripts.md).

Whisper transcription runs locally on the phone. Requested AI operations send selected transcript context to the connected provider, using the connected account's access and allowance. Episode audio is not uploaded for cloud transcription. Interest-based suggestions send followed-show titles only when that option is selected; suggestions are initiated explicitly.

## Backups and exports

Library backups contain readable personal library data: subscription and episode metadata, progress, queue, transcripts, saved moments and stored generated text, plus language/model choices. Manual Drive backup uses Android’s document picker; the selected provider manages cloud sync. Managed backups are ZIP histories containing readable library data and referenced infographic images, with no passphrase encryption. Image-bearing snapshots use a bounded binary wrapper; older JSON snapshots remain readable. Persistent access to the user-selected file is stored only on that installation. Verified recovery copies stay in private app storage. They exclude account credentials, downloaded audio, model binaries and device-private file paths. OPML contains subscription titles and feed addresses. Choose destinations carefully and share exports only deliberately.

Do not include personal backups, feeds containing access tokens, recordings, account data or private screenshots in issue reports. A minimal reproduction on a development emulator is preferable. Repeated AI requests may consume additional plan allowance.

### Social post drafts

Posts, research references, generated illustrations and original source snapshots remain on the device and are included in manual library backups. Existing library sync does not upload posts. Choosing a format or requesting a change sends the saved excerpt and relevant current content/instructions through the connected ChatGPT account. Infographics additionally use hosted web research on a public-topic question derived from the excerpt, followed by a reviewed brief and vector illustration specification. The provider can observe those queries and requests. The app renders illustration shapes and exact typography locally. Reopening, copying or sharing a completed result does not make an AI request. Generation uses the connected account’s allowance; interrupted work requires an explicit retry.

Sharing exports the selected text/image to another app. Image exports in the app cache are available through temporary URI grants and cleaned after seven days on a subsequent export; images explicitly saved to Pictures/DistillPod remain until deleted by the user. Draft deletion does not recall exports or content already shared externally.


When the optional voice preview is enabled, the owner can save an OpenAI project API key on the phone. A separate Android Keystore key encrypts this credential in app-private preferences. It is excluded from library backups, portable settings and sync, and can be replaced or removed in Settings → Voice. Voice sends the spoken question and selected transcript context directly to OpenAI using this key; there is no voice credential broker or Google sign-in requirement. Raw recordings are transient. The most recent answer and content-free usage diagnostics remain in process memory. See [voice preview](android/voice-mode.md) for validation limits.

Optional usage statistics have their own default-off control in Settings → Diagnostics. Approved feature/action counts and trigger categories contain no podcast content. Diagnostic operation summaries persist privately for up to 72 hours (maximum 500 revisions) and are excluded from backups/sync. Revocation purges pending envelopes; provider retention governs reports already delivered. See [reliability analytics](android/reliability-analytics.md).
