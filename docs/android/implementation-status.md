# Android implementation status

Reviewed against `main` on November 5, 2026. This page distinguishes implemented code, downloadable releases and dated device evidence.

## Source and distribution

| Item | State at this review |
| --- | --- |
| Main source | `0.11.0-alpha.1`, version code `20`, from [gradle.properties](../../gradle.properties) |
| Latest published APK | [0.10.1-alpha.1](https://github.com/andrepaim/distillpod/releases/tag/v0.10.1-alpha.1), published November 5; APK, checksums and build metadata attached |
| Latest recorded phone update | S25 in-place installation of 0.10.1-alpha.1, code 19, November 5, before GitHub publication |
| Database | Room schema 8 with migrations from schemas 1–7; no destructive fallback |
| Supported Android/package | Android 10+ (`minSdk 29`), ARM64 and x86_64; `dev.distillpod.android` |
| Default source/debug build | No production Firebase configuration; library sync shows unavailable |
| Release workflow | Requires Firebase client configuration, validates an unsigned candidate and retains the personal-alpha signing identity |

The phone update retained its certificate, Firebase client configuration and existing library data; the online-transcript setting was visible and off. This is update/data-preservation evidence, not a live phone sync test or a store release. See the [online-transcript installation report](online-transcripts.md#s25-installation--november-5). Release notes in [current.md](../releases/current.md) describe the 0.11.0-alpha.1 social-sharing candidate. The latest downloadable build is listed separately above until publication.

## Implemented on main

- **Library and discovery:** Room storage, public search through Apple's iTunes directory, direct RSS/Atom subscriptions, conditional refresh, cached browsing and OPML transfer. Feed parsing retains the latest 200 entries. The library has a searchable, alphabetically ordered show gallery; Saved and transcripts have text search, and Settings is grouped by task.
- **Listening:** Media3 background streaming/offline playback, original-timeline position, speed, played controls and a persistent ordered queue. The full player rewinds 10 seconds and advances 30 seconds. Downloads support bounded storage, range/validator resume, cancellation and protection for active/queued media.
- **Transcripts:** suitable publisher transcripts, optional read-only PodcastTranscript.ai lookup for approved public shows, then local Tiny/Base Whisper. Online lookup defaults off and never uploads audio or creates a cloud transcription job. Local chunks keep durable coverage, progress and pause/resume. Full-episode AI requires complete text; saved audio windows and automatic ad skipping require publisher/local timing. [Controls and validation](online-transcripts.md).
- **Saved moments and AI:** preceding-minute captures persist before preparation or generation. Connected ChatGPT supplies quotes/insights, summaries/chapters, grounded episode chat, transcript-only saved-idea explanations and requested podcast suggestions with checked feeds. Captures survive failures for review/retry. Ad skipping is reviewed, opt-in, source-bound and limited to verified downloaded audio. Web research supports social infographics; saved-idea explanations remain transcript-only.
- **Social sharing:** two autonomous formats, Quote & discussion and researched Infographic; native text/PNG sharing, natural-language revisions, source checks, background jobs, retained previous versions and portable image assets. [Design and evidence](social-sharing-redesign.md).
- **Backup and restore:** remembered document access, one-tap saves, bounded dated ZIP history, byte read-back and private recovery copies. Standalone JSON remains supported. Restore previews and merges missing records while keeping existing data; portable model/language/AI-model preferences are optional. Credentials, downloaded media and models are excluded. [Backup guide](drive-backup.md).
- **Optional Firebase library sync:** Google sign-in, one-account library binding, public-feed review, durable offline changes, progress, completion and saved moments. Protocol 2 adds queue and portable preferences, with explicit conflict choices. Full transcripts, analyses, conversations and explanations stay local in the selected Spark deployment; the experimental Storage adapter is disabled by `sync_content_enabled=false`. [Usage and configuration](subscription-sync.md).
- **Identity and release:** encrypted ChatGPT credentials, application-owned authorization/recovery and serialized renewal. Google sync sign-in is separate. CI gates signed alpha publication on Kotlin, lint, native, Firebase and isolated Android acceptance. [Release process](../releases.md).

## Validation evidence

These are dated results, not claims that every scenario was rerun for the current source. Receipts identify the tested revision, device and limitations.

| Area | Recorded evidence and boundary |
| --- | --- |
| Online transcripts and upgrades, November 5 | [Validation receipt](evidence/online-transcripts-validation.json): 248 JVM tests (82 core, 166 app), debug lint and app/instrumentation builds. JVM upgrade regression covers schemas 1–7. Device tests were compiled at that checkpoint; the later S25 update separately verifies preservation. |
| Library sync, November 4 | [TDD receipt](evidence/firebase-sync-tdd.md): protocol/rules, Room and independently authenticated demo clients, queue/preferences, finished-content experiment and force-stop recovery. Live Google sign-in/empty-library sync was checked on a visible emulator; synthetic fixtures used a separate demo environment. |
| Backup history | [Standalone backup receipt](evidence/drive-backup-validation.json): physical Drive JSON round trip. The later [remembered-history flow](drive-backup.md) has JVM/provider and isolated system-picker coverage; its physical Drive validation remains outstanding. |
| Library, audio and AI on S22 | [Product tests](evidence/s22-product-tests.txt), [capture](evidence/s22-live-capture-tests.txt), [renewal/restore](evidence/s22-renewal-restore-tests.txt), [understanding](evidence/s22-understanding-tests.txt) and [exploration](evidence/s22-exploration-integration.json) cover their stated earlier builds. |
| Complete local episode | [Nine checks](evidence/s22-complete-episode-tests.txt) and [metadata](evidence/s22-full-episode.json): a 646.426-second enclosure, six chunks, 161 segments and complete coverage in 94.457 seconds after timestamp/codec-tail corrections. This is not broad language-accuracy evidence. |
| Locked-screen playback | [Hour protocol and result](background-playback.md): 60m16.841s on S22 with no errors/underruns or rebuffering, plus synthetic focus and restart checks. USB-connected/muted conditions do not establish unplugged battery or real Bluetooth reliability. |
| Earlier UI and icon | [Navigation/large text](evidence/s22-ui-redesign-tests.txt), [search build](evidence/search-ui-build.json) and [icon build](evidence/launcher-icon-build.json) record the earlier 0.3/0.4 builds, not acceptance of the later gallery/settings revision. |

The current [CI workflow](../../.github/workflows/ci.yml) runs JVM tests, lint/debug assembly, release-tool guards, host native checks, Firestore/Storage rules and isolated Android acceptance. Release runs additionally build/lint an unsigned configured release candidate. Ordinary CI excludes live account and opt-in public/long-running tests. See [development](../development.md) for reproducible commands.

## Remaining acceptance

This remains a personal alpha. Real Bluetooth/telephone interruptions, simultaneous playback/transcription, multi-hour English/Portuguese quality, sustained unplugged battery use, Android job quotas over days, TalkBack/localization, broader devices/ABIs and the seven-day owner trial need further evidence. Earlier large-text checks do not establish accessibility of every current screen.

Multiple local sync profiles, complete cloud account erasure, independent dated cloud recovery/retention, App Check, staging and live telemetry remain open. The telemetry consent/event boundary exists in core; Analytics, Crashlytics and Performance collection are not enabled. Cloud sync does not replace independent backups.

The older web app is archived on `legacy/web` and tagged `web-legacy-final`. The owner chose a fresh Android library; automatic migration from the server/browser database remains deferred. Public-store distribution and a future signing transition require separate work. Historical acceptance checklists in the [original Android plan](../android-implementation-plan.md) and [Firebase plan](firebase-sync-telemetry-plan.md) remain open where their full criteria lack evidence.
