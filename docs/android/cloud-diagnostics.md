# Cloud diagnostics

Optional cloud diagnostics use Sentry. The configured S22 candidate has delivered a log and handled error to the owner project, with readable managed stack frames. Publication status is tracked separately in GitHub Releases.

## Setup and use

Create a free Sentry Developer organization and Android project. Store its public client DSN in the repository Actions secret `SENTRY_DSN`. Release validation requires it; ordinary source builds remain unconfigured. For a configured local build, provide `SENTRY_DSN` in the build environment without printing it. The app accepts HTTPS ingestion on Sentry-hosted domains. No Sentry management/authentication token is embedded in the app.

Open Settings → Diagnostics and enable Share diagnostics. This consent is device-local, off by default, independent of library sync and accounts. Send test diagnostic emits a harmless `diagnostics.test` log and a deliberately handled error through the real reporting path; it does not crash or invoke AI. Confirm arrival in Sentry's Logs view; a queued event or successful local logging call does not prove cloud ingestion. Test traffic belongs in staging. Non-debuggable builds use production.

Copy diagnostic reference includes app version, random installation/session identifiers and the current time. Include the approximate incident time, action tried, expected behavior and observed result in a bug report. The installation reference supports searches for earlier sessions after restarting the app. It is not a Google identity. Do not include credentials or podcast content.

## Events

The allowlisted events cover headphone command receipt/trust/mapping/debounce decisions, capture persistence/preparation, transcription scheduling/wait conditions/stage transitions, distillation execution/outcomes, playback lifecycle/errors, voice start/finish/errors, and selected worker/sync failures. Critical operation events include random capture IDs or transcript revisions; capture dependency events link a known transcript revision. Distillation attempts are distinguishable. A dependency created after the initial capture may require correlating the adjacent session events; this is not complete distributed tracing.

Logs include event/session/installation identifiers, source, safe reason categories, Android API and connectivity category; Sentry includes release identity. Exception messages, causes, request payloads, user identity, arbitrary extras, breadcrumbs, thread listings, local paths and source context are removed before queueing reports. Screenshots/view hierarchies, automatic navigation/network breadcrumbs and session tracking are disabled. No audio, transcript, prompts, answers, titles, private URLs, tokens or email are intentionally collected. This still sends technical diagnostics to a third-party provider; review actual serialized payloads before rollout.

Expected network/power waits are logs rather than exception reports. Cancellations do not create nonfatal exception reports. A missing headphone event cannot show whether Android ever delivered a physical press. No per-second playback or per-token voice logging is used.

## Delivery and bounds

The Android SDK serializes log/error envelopes into a separate app-private outbox under `noBackupFilesDir`. A custom synchronous log processor persists SDK log envelopes without waiting for the normal in-memory batch flush. Application log calls run on a bounded background executor; events in that executor can still be lost on abrupt process termination. Crash reporting persists through the transport separately. This is best-effort diagnostics, not a transactional audit trail.

WorkManager uploads when connected, with an initial short delay, exponential retries and a periodic 15-minute recovery job (Android may defer either). Successful HTTP acknowledgements remove envelopes. Temporary failures, authorization failures and rate limits preserve pending data. Permanent payload rejections are discarded and surfaced in the status. No custom backend is required. The upload client has no application authentication interceptor and does not follow redirects.

The outbox is bounded to 5 MB / 1,000 files and 72 hours. Large envelopes over 256 KB are dropped. Collection is capped per installation at 1,000 log envelopes and 30 error envelopes per UTC day; errors reserve a separate allowance. Old entries are evicted under pressure. Queued network requests can be delivered more than once after process death; event identifiers permit detecting duplicates. Rate limits conservatively pause all diagnostic traffic rather than retrying continuously. These limits are not an organization-wide quota guarantee.

Revoking consent closes reporting, cancels diagnostic work and clears queued data. Already in-flight requests may finish. Provider retention/deletion controls apply to transmitted reports. Force-stopping Android prevents scheduled upload until the app can run again.

## Scope and evidence boundaries

The candidate includes managed Android crash/ANR SDK integration and sanitized handled errors. Native Whisper crash collection/symbol upload and R8 symbol automation are not implemented. The [reliability analytics increment](reliability-analytics.md) adds bounded persistent operation reconciliation, versioned dashboard definitions and an aggregate report with explicit denominators. The release currently does not enable R8 minification. Do not claim native crash diagnosis or complete success-rate dashboards from these events.

Acceptance for merge/release includes: automated consent/redaction/outbox checks, upload retry/acknowledgement checks, regression tests/lint/build for both voice configurations, configured S22 in-place upgrade/UI checks, a staging log and handled error observed remotely, an offline/reconnect check, and review of the resulting production build configuration. Deliberate crashes and destructive tests run only on a disposable emulator. No live AI request is needed to validate reporting transport.

Provider references: [Android logs](https://docs.sentry.io/platforms/android/logs/), [Android filtering](https://docs.sentry.io/platforms/android/configuration/filtering/), [pricing](https://sentry.io/pricing/). Keep the organization on Developer; no paid-overage authorization is implied.

## Candidate validation (2026-11-06)

- Default voice-disabled build: 91 core + 324 app JVM tests, debug lint and APK assembly passed before the delivery test was added.
- Voice-enabled build: 325 app JVM tests (including consent/redaction/outbox and HTTP delivery tests), debug lint and assembly passed. Tooling: 22 Python tests passed.
- Physical S22: candidate code 33 installed in place over private code 27 using the retained certificate; app launched, Settings → Diagnostics was reached, and the unconfigured state/default-off consent were visible. This did not validate real headphone delivery, live AI, crash/ANR ingestion, offline upload recovery or the final release artifact.
- The owner project is provisioned and the release DSN is stored in GitHub Actions. A configured S22 candidate delivered `diagnostics.test` and a handled error, `DISTILLPOD-ANDROID-1`, with readable `Diagnostics.kt` frames and sanitized message `Diagnostic failure: internal`. Offline reports survived a force-stop/relaunch (3 pending envelopes became 5 after startup events); Wi-Fi/mobile-data settings were restored. After reconnection the queue drained to zero and Sentry returned matching `network:offline` logs with their original timestamps, establishing remote recovery. The phone candidate used code 33 before the release metadata bump; it is not evidence of a published code-34 binary.
