# DistillPod Android guide

DistillPod is a native Android personal alpha with a local library, background podcast playback, on-device Whisper and optional ChatGPT episode understanding. Documentation follows `main`; features in published APKs can lag behind the source.

Start with the [documentation index](../README.md), the [two introductory demos](../demos/README.md) or [troubleshooting](../troubleshooting.md).

Use these guides for current instructions:

- [Getting started](../getting-started.md): installation, search, playback, transcripts, saved moments, ChatGPT and optional Google library sync.
- [Headphone capture](headphone-distill.md): optional previous-track capture while listening.
- [Android Auto](android-auto.md): car setup, controls and assistant-search limitations.
- [Contextual distills](contextual-distills.md): source selection, review and previous versions.
- [Drive backup and restore](drive-backup.md): remembered backup files, dated snapshots, transfer and recovery.
- [Library sync](subscription-sync.md): supported data, feed approval, account binding, conflicts and Firebase configuration.
- [Existing online transcripts](online-transcripts.md): opt-in lookup, source matching and timing limitations.
- [Privacy and data handling](../privacy.md): local storage, network requests, cloud records and exports.
- [Build and test](../development.md): pinned tools, Gradle commands, native checks and isolated emulator suites.
- [Signed APK releases](../releases.md): publishing, configuration and upgrade-compatible signing.
- [Implementation status](implementation-status.md): source version, published release and dated validation evidence.

## Developer diagnostics

**Settings → Developer checks** retains the earlier sign-in, manual model/clip import and isolated inference harness. Its ten-minute clip limit applies to diagnostics. The episode pipeline handles longer sources through bounded chunks and persisted coverage. Native model loading cannot be interrupted midway; cancellation is checked immediately afterward.

Fixture suites belong on a development emulator. Live account, public-network and long playback tests require deliberate opt-in; their commands and limits are in the [development guide](../development.md#deliberate-device-checks). Preserve the owner's account, models, downloads, queue and listening state during physical-device work.

The [0.4.0 personal-alpha guide](personal-alpha.md), [personal build progress](personal-build-progress.md), design proposals and individual validation reports describe dated builds. Use their evidence for the stated build and device, rather than treating them as current installation instructions.
