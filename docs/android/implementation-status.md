# Android implementation status

Branch: `feat/native-android`. Updated November 3, 2026.

The native personal alpha replaces the feasibility launcher on the owner's S22 Ultra. The original diagnostics remain in Settings → Developer checks. The application ID, signing certificate and encrypted ChatGPT connection are preserved across upgrades. The owner explicitly chose **a fresh Android library**; existing server/browser migration is outside this delivery, and the web application remains intact.

## Delivered application

- Room library with schema 1→2 migration and exported schemas, separate from the old harness database. Its non-destructive feasibility import preserves any old episodes, progress and moments without copying or clearing credentials.
- Public podcast search, direct RSS/Atom subscriptions, safe plain descriptions, conditional refresh, cached browsing and OPML transfer. Streaming feed parsing retains the latest 200 entries without loading a complete large archive.
- Media3 background streaming/offline player, original-timeline progress, seek, speed, played controls and ordered queue.
- Researched and implemented 0.3.0 UI: resume card, adaptive show section, compact episode cards, artwork mini-player/queue shortcut, focused player with correct 15/30-second labels, organised queue, quote-focused Saved collection and wrapping controls. [Proposal and verification](ui-improvement-proposal.md).
- Constrained persistent downloads, safe range/validator resume, cancellation that waits for the writer to close, pinned model hashes/sizes, storage limit, protected active/queued files and unused-cache cleanup.
- Publisher transcripts and local Whisper chunks with bounded decode, durable coverage/revisions, prioritized capture windows, pause/resume, model/language controls and recovery. Compressed chunks retain actual decoded source timestamps; integer frame limits prevent rounding overflow. Incomplete coverage produces a recoverable failure rather than an unscheduled queued job.
- Preceding-minute captures saved before AI work, source-review gating, quote/excerpt validation, auth/quota/interruption recovery, saved library, copy/share and source replay.
- Bounded summary/chapter analysis with completed-part checkpoints, sorted validated timestamps and source seeking. Ads are reviewable, disabled by default, tied to the transcript/audio revision, and skipped only in verified downloaded audio. Deliberate seeking/Undo replays a range normally.
- Persistent grounded episode chat with bounded retrieval/history, streamed incomplete replies, validated source references and retry without duplicate user questions. Unsupported answers are visibly qualified.
- Saved-moment explanations explicitly labeled as transcript-only, with source provenance and text export/share. **Web research is not enabled** and no web citations are invented.
- Requested podcast suggestions using up to three AI directory searches, at most nine feed checks and six verified results. Followed/dismissed feeds stay excluded; sending followed-show titles is an explicit opt-in. No automatic AI recommendation schedule is enabled.
- Original credential vault plus application-owned authorization, encrypted pending-attempt recovery, registration reuse, selected-account matching, serialized renewal, completion-only commits and explicit quota recovery. Retries carry request identities so old callbacks cannot overwrite a newer attempt.
- Settings → Backup & Restore remembers a user-selected Drive/file-provider document for one-tap saves and an in-app dated history. It keeps bounded snapshots, byte read-back, private recovery copies and optional portable preferences; standalone JSON transfers remain supported. Versioned JSON library backup retains checksum/limits, schema upgrade, preview and transactional merge. See the [backup guide](drive-backup.md) and [validation receipt](evidence/drive-backup-validation.json). Feed aliases/scoped GUIDs map to existing local identities, existing records win, and changed source timings are invalidated. Credentials, model binaries and device audio paths are excluded; interrupted restored AI work requires explicit retry.

- Search/discovery 0.4.0: publisher thumbnails, preview sheets, featured first result, creator/category metadata, Follow/Open states, topic tiles, local recent searches, RSS entry and distinct loading/empty/retry states. Canceled or obsolete requests cannot overwrite newer results. [Proposal and result](search-ui-proposal.md).

## Current evidence

Installed **0.4.1-alpha** adds the owner's selected lavender Listening Drop launcher icon, with adaptive and monochrome resources. The [icon build receipt](evidence/launcher-icon-build.json) records build/lint, matching installed APK and signing certificate, visual S22 home-screen inspection, successful icon launch, and unchanged library/account/settings/playback state. Its changes are limited to icon resources, manifest references and version metadata.

[The preceding 0.4.0 search build receipt](evidence/search-ui-build.json) records the behavior suite and cleanup. That local suite passes 48 core and 62 app JVM tests, lint and both APK assemblies. Three device flows pass; both search flows additionally pass at Android system font scale 160%. Those behavior suites were not rerun for the icon-only revision. The [earlier playback receipt](evidence/background-playback-build.json) identifies the hour-test build; service behavior is unchanged.


Physical S22 evidence:

- [UI navigation and action flow](evidence/s22-ui-redesign-tests.txt): resume/pause, mini-player, actual seek/speed, queue reorder without position loss, saved source navigation, filter empty-state recovery and controls at 160% text. Owner queue/position/speed/volume are restored and fixture rows/audio removed.

- [Eight production/audio checks](evidence/s22-product-tests.txt): subscribe, stream, download, publisher transcript, capture, Saved, recreation, replay, plus a repeated 220-second native source split into pause/resume checkpoints.
- [Real account capture](evidence/s22-live-capture-tests.txt), [deliberate renewal and restore](evidence/s22-renewal-restore-tests.txt): production quote/insight completion, a real token renewal followed by completed inference, and restore into a separate database. No credentials or generated result text are exported.
- [Summary/chat/ad-review checks](evidence/s22-understanding-tests.txt): schema 1→2 retains progress, moments and messages; reviewed skip/replay/source UI works; the real account completes summary and grounded reply workers.
- [Six final phone checks](evidence/s22-final-phone-tests.txt): the updated core, migration, public network, large Naruhodo feed, and Tiny/Base with real English/Portuguese clips. [Benchmark notes](podcast-benchmark.md) distinguish speed measurements from unmeasured language accuracy and sustained behavior.
- [Live exploration metadata](evidence/s22-exploration-integration.json): a public-source explanation and six real verified feed suggestions, using isolated library/preferences and no owner history.

The complete public-episode test found and reproduced codec frame rounding and seek-offset coverage defects. The fixes bound selected frames, preserve actual PCM timestamps and handle verified codec-padding tails. The corrected [complete-episode device run](evidence/s22-complete-episode-tests.txt) passed all nine checks. A 646.426-second downloaded enclosure completed six chunks, 161 segments and full coverage in 94.457 seconds; [metadata](evidence/s22-full-episode.json) records the result. Files without a reliable duration fail clearly rather than publishing a partial episode as complete.

## TDD evidence

Tests were written and run before their implementations. Expected failing compilation identified missing transcript/queue/feed/OAuth/job APIs, WAV decoding, Android persistence/identity/stream classes, the provider, credential envelope, native bridge header, authorization session and Compose screen. Implementations were added after those failures. Subsequent failures and final outcomes belong in the validation receipt below.

Core tests cover boundary timestamps, coverage gaps, GUID collisions, queue load failure, malformed feeds and external entities, timed subtitles, invalid WAV formats, callback spoofing, state duplication, consent denial, PKCE, interrupted jobs and authenticated credential encryption.

Android JVM tests use Robolectric and MockWebServer for persistence, signed JWT claims/signatures, consent scope denial, token exchange, streamed response interruption, request configuration, quota classification and screen actions. These are simulated Android tests, not physical-device evidence.

Native tests use explicit runtime checks, including Release builds, rather than assertions that disappear under NDEBUG. Real inference uses the upstream JFK sample and a separately downloaded tiny.en model. No account credentials, models or personal audio are committed.

## Remaining acceptance and release work

This is a personal alpha for an owner trial. The real locked-screen hour, synthetic audio-focus interruptions and force-stop/reopen checks passed. Real Bluetooth/telephone interruptions, simultaneous playback/transcription, multi-hour English/Portuguese quality, unplugged battery behavior, Android job quotas over days, TalkBack/large-text/localization review, broader device/ABI checks and the seven-day trial remain open. The USB-connected hour does not establish those remaining scenarios.

Web research/tool availability and public distribution are separate work. The app uses a labeled transcript explanation when no web-research adapter is enabled. The owner chose a fresh library, so server/browser migration is deferred rather than performed against an unknown remote deployment.

The APK retains the local development signing certificate so it upgrades the existing installation without deleting the account. It is not presented as a public-store release. No API-key fallback, separately billed provider, mandatory server or automatic AI schedule has been introduced. The full [implementation plan](../android-implementation-plan.md) retains unchecked acceptance criteria where evidence is incomplete.

## Final installed acceptance

The final APK passed [all nine installed-build checks](evidence/s22-installed-alpha-acceptance.txt), including complete-episode transcription after both compressed-timestamp corrections and the duration-verification guard. The installed APK checksum matches the versioned release artifact. Model files are retained for daily use; the separate fixture/test package is removed after validation.

## Transcript progress correction — 0.2.1-alpha

Native progress/segment callbacks now update an animated preparation bar and active audio counter within each chunk. Loading/decoding/saving phases remain visible, while saved coverage still commits only after chunk validation. See [the progress correction](transcription-progress.md) and its device evidence.

## Background playback validation

The player explicitly enables its Media3 CPU wake lock for screen-off playback. The physical S22 completed 60m16.841s locked playback with zero errors/underruns, no rebuffering and only 4 ms final position drift. Synthetic transient/permanent audio-focus checks and force-stop/reopen restored position, queue and speed correctly. The owner queue/settings were restored and test artifacts removed. [Protocol, results and limitations](background-playback.md) include the brief light thermal status and the USB/muted setup. Real Bluetooth remains open because no hardware was available.
