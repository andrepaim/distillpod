DistillPod 0.18.0-alpha.1 expands optional diagnostics with operation tracking and aggregate reliability views, making it easier to investigate missing transcripts, failed distillations and voice interruptions.

- **Separate privacy controls:** Settings → Diagnostics now offers independent diagnostic and usage-statistics preferences. Usage defaults off; your existing diagnostics choice is retained. Audio, transcripts, questions, answers, podcast titles, URLs and credentials are excluded.
- **Recovery-aware reports:** bounded private operation records survive app restarts, distinguish retries and reconcile unfinished transcription/distillation work with saved app state. Missing outcomes remain visible rather than being counted as successful.
- **Audio and transcript signals:** reports distinguish voice recording, text, audio output and playback restoration requests, with transcription stages, coverage/empty-output signals and playback buffering events.
- **Reporting status:** see pending envelopes, locally detected dropped reports and the last upload acknowledgement. Five Sentry dashboard definitions and a deduplicating aggregate-report tool accompany this release. Dashboard outcome counts describe observed transitions; the report computes latest observed job outcomes.

Install as an update without uninstalling. Version code **35** supersedes code 34. Application ID, retained signing identity, library database, backups and sync protocol remain compatible. Voice preview remains enabled in the distributed APK; paid content sync remains disabled.

Collection is optional and best effort. Android can defer uploads; force-stop requires reopening the app. Limits, offline periods and abrupt termination can leave gaps. Native Whisper crash reporting is not included. Output submission/restoration requests cannot prove that audio was audible. Dashboards cover consenting reporting devices, exclude synthetic tests and use the provider's available query window. Broader regression alerts and long-term rollups require baseline data.

Validation includes 423 JVM tests, 24 tooling tests, both voice build configurations and configured release lint/assembly. Physical-device and cloud evidence are recorded in the [delivery receipt](https://github.com/andrepaim/distillpod/blob/v0.18.0-alpha.1/docs/android/evidence/reliability-delivery.md). Publication additionally requires exact-commit main CI and the signed-APK release checks.

See [reliability analytics](https://github.com/andrepaim/distillpod/blob/v0.18.0-alpha.1/docs/android/reliability-analytics.md) for metric definitions, dashboard links and privacy details.
