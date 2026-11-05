# Privacy and data handling

DistillPod keeps its library, queue, listening position, transcripts, saved excerpts and generated text in its private Android storage. ChatGPT credentials are encrypted with a key held by Android Keystore. Optional Firebase sign-in uses Firebase's SDK-managed app-private credential storage. Android application backup is disabled; neither provider's credentials enter library exports or library sync. This is a personal-alpha implementation; the repository's validation reports are not an independent security audit.

## Current Spark-only deployment

Billing is disabled. Queue and portable preferences can synchronize through Firestore without enabling Cloud Storage. Transcripts and other finished text remain local; their experimental Storage adapter is disabled in builds. Manual Backup & Restore remains available for text transfer.

## Optional library sync

The sync feature branch adds Google sign-in and Firestore storage for approved public subscriptions, listening progress, completion and saved moments. Cloud records include public feed/media addresses, feed-scoped episode identity and minimal display metadata, session/sequence identifiers, original positions/intervals, saved excerpts and completed moment quotes/insights with their model/source provenance. Record versions, operation receipts and ordered changes support retries and conflict review. The extended scope adds queue and portable model/language/AI-model preferences. Full transcripts, episode analyses, conversation branches and explanations remain local: the experimental Cloud Storage adapter is disabled. Full RSS catalogues are refreshed from their source. Downloaded audio, models, unfinished computation and device policies remain local. Local job states, attempts and private file paths never enter sync.

On first connection, review which existing feeds to share. Existing and manually added sources are not uploaded automatically, because private RSS access tokens can be embedded in URL paths. Directory-result follows are treated as public sources. Password-bearing or query-bearing feed/media addresses are excluded even when the source is selected. Oversized records stay local. Feed-sharing choices are local and must be reviewed again after a manual library restore; cloud-restored subscriptions retain the account's prior sharing decision.

Firebase Authentication receives the Google identity used to sign in. The library stays bound to its original Firebase user ID. A different account cannot adopt its outbox or upload its retained local data. Signing out pauses cloud work and keeps the offline library. Multiple local account profiles are not implemented yet. Unfollowing creates a cloud deletion marker while preserving local episode data; this does not erase historical cloud changes. Complete account erasure and independent dated cloud recovery are later milestones.

Cloud records are protected by per-user security rules and the provider's transport/storage encryption. This is not end-to-end encryption; the project administrator can access stored data. The database is in São Paulo; the content bucket is configured in the same region at deployment. Sync uses bounded work and retries, with foreground/network opportunities and periodic recovery. Android idle and force-stop can defer work; reopening the app resumes it.

## Telemetry status

The feature branch contains a tested event/consent boundary. Firebase Analytics, Crashlytics and Performance SDK adapters and live collection are not yet enabled. Enabling library sync does not opt into telemetry.

## Network requests

Podcast directory searches send the query to the directory provider. Following/refreshing a feed, loading its artwork and streaming/downloading an episode contact the corresponding hosts. Model downloads contact their configured upstream host. These services can observe the requests made to them.

Optional existing-transcript lookup sends the episode title to PodcastTranscript.ai for approved public sources. It defaults to off, never submits audio or creates a cloud transcription job, and does not share the feed/enclosure URL, listening progress, saved moments or credentials. The provider can observe the query and network address. Already downloaded text remains readable when lookup is disabled. Consent and lookup cooldowns stay on this installation. See [the lookup design](android/online-transcripts.md).

Whisper transcription runs locally on the phone. Requested AI operations send selected transcript context to the connected provider, using the connected account's access and allowance. Episode audio is not uploaded for cloud transcription. Interest-based suggestions send followed-show titles only when that option is selected; suggestions are initiated explicitly.

## Backups and exports

Library backups contain readable personal library data: subscription and episode metadata, progress, queue, transcripts, saved moments and stored generated text, plus language/model choices. Manual Drive backup uses Android’s document picker; the selected provider manages cloud sync. Managed backups are ZIP histories containing readable JSON, with no passphrase encryption. Persistent access to the user-selected file is stored only on that installation. Verified recovery copies stay in private app storage. They exclude account credentials, downloaded audio, model binaries and device-private file paths. OPML contains subscription titles and feed addresses. Choose destinations carefully and share exports only deliberately.

Do not include personal backups, feeds containing access tokens, recordings, account data or private screenshots in issue reports. A minimal reproduction on a development emulator is preferable. Repeated AI requests may consume additional plan allowance.

### Social post drafts

Post drafts and their original source snapshots remain on the device and are included in manual library backups. Existing library sync does not upload drafts. Explicit AI composition/rewrite actions send the saved excerpt, current post content and optional personal perspective through the connected ChatGPT account. Opening the editor or rendering a card does not make an AI request.

Sharing exports the selected text/image to another app. Image exports in the app cache are available through temporary URI grants and cleaned after seven days on a subsequent export; images explicitly saved to Pictures/DistillPod remain until deleted by the user. Draft deletion does not recall exports or content already shared externally.
