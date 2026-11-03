# DistillPod Android

Native personal alpha with a phone-owned library, background podcast playback, local transcription and ChatGPT plan-backed quotes/insights. See [implementation status](../android/implementation-status.md), [personal build progress](../android/personal-build-progress.md) and the [full plan](../android-implementation-plan.md).

## Use the app

1. Open Search, find a podcast or enter its RSS URL, then Follow/Add RSS feed.
2. Open an episode to play, download, queue or prepare its transcript. Playback works without an AI account. Downloaded audio plays without a network connection.
3. In Settings, select Tiny or Base and download the model if local transcription is needed. Choose Auto, English or Português. Timed publisher transcripts are used when available; otherwise Whisper prepares bounded chunks on the phone. Pause/resume keeps completed coverage.
4. Connect ChatGPT in Settings only if it is not already connected. Approve plan use in the browser and return to DistillPod. Existing installed credentials are retained by an APK upgrade. An approval email or Connected badge alone does not prove inference access.
5. While listening, select Distill last 60 seconds. The original source window is saved immediately. Preparation or AI failure preserves it. A capture made before its downloaded source was verified asks for source review before AI generation.
6. Saved holds quotes, insights and excerpts. Open a moment to replay its original source, copy/share it or retry a failed request. Retries can consume additional plan allowance.
7. Summary & chapters opens bounded episode analysis; chapter buttons replay the original timeline. Ask episode stores grounded questions/replies and provides validated source buttons. Ad ranges require individual review plus the Skip approved ads switch; Undo or seeking into a range replays it normally.
8. A saved moment can be explained from its transcript and exported/shared as text. This mode does not search the web. Search → Explore by interest produces verified feed suggestions only when requested; sending followed-show titles is optional.
9. Settings offers library backup/restore, OPML subscriptions, network/power constraints, unused-cache cleanup and notifications.

Only selected transcript text goes to OpenAI for requested AI actions; audio transcription runs locally. No API key, separate billed fallback or mandatory DistillPod server is used. Account/model/tool eligibility and plan limits still apply. No web citations or research capabilities are assumed.

## Backup and transfer

Settings → Backup & Restore → Save backup to Google Drive writes a versioned JSON document through Android's document picker. It includes library metadata, progress, queue, transcripts and saved text, including any future analysis/history stored in the library. It excludes account credentials, audio, model binaries and device-private file paths. The file is readable library content; choose a suitable destination.

Restore backup validates size, record limits, format, checksum and schema, then shows a preview. Merge library keeps existing records and inserts missing records in one transaction. Repeated merges are idempotent. Invalid relationships roll back the complete merge. Interrupted AI work is restored for explicit retry. Re-download audio and connect the destination installation's account separately.

See the [Drive backup guide](drive-backup.md) for portable preferences, file verification and cloud sync behavior.

OPML transfers subscriptions only. Import stores feeds locally, avoids repeated subscriptions and schedules ordinary feed refresh. The owner chose a fresh Android library. Server/browser migration is deferred; this backup does not import the older web application's full database.

## Build and automated tests

Requires JDK 17, Android SDK platform/build-tools 35, NDK 27.2.12479018 and CMake 3.22.1. Set `ANDROID_HOME` to your SDK or create an ignored `local.properties` containing `sdk.dir=/path/to/sdk`.

```sh
git submodule update --init --recursive
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK: `../../app/build/outputs/apk/debug/app-debug.apk`. It is a development APK using a local debug signing key, not a production-signed release. Supported packaged ABIs are ARM64 and x86_64; minimum Android version is 10.

## Focused validation

The physical S22 Ultra has passed production library/playback/download/publisher transcript/capture/replay and bounded native chunk pause/resume checks. A deliberate real-account capture and refresh/restore checks are recorded under [device evidence](../android/evidence). Repeated public speech fixtures test pipeline mechanics, not representative multilingual quality or sustained battery behavior.

With an unlocked development emulator:

```sh
DISTILLPOD_SERIAL=emulator-5554 scripts/emulator-tests.sh /path/to/fixtures
```

Fixtures contain upstream `jfk.wav`, `ggml-tiny.en.bin` and multilingual `ggml-tiny.bin`; CI downloads and checks model SHA-256 values. The script adds the pinned upstream MP3 sample. Set `DISTILLPOD_ADB` for Windows ADB from WSL and optionally `DISTILLPOD_ADB_PORT`.

The full diagnostics suite manipulates imported clip fixtures. Use a development emulator. Production tests isolate their own podcast rows and preserve the ordinary account vault. The pipeline test leaves a verified Tiny multilingual model available in Settings.

Live account requests are excluded from ordinary CI. A deliberate device run can select `dev.distillpod.android.LiveCaptureDeviceTest`, `LiveUnderstandingDeviceTest` or `LiveExploreDeviceTest` and pass instrumentation argument `allowLiveAccount=true`; `refreshLiveAccount=true` additionally exercises real refresh-token renewal. It uses a public speech excerpt, does not print tokens or result text, and removes its fixture rows afterward. Do not enable these arguments on automated pull-request runs.

## Developer diagnostics

Settings → Developer checks retains the older sign-in, manual model/clip import and isolated inference harness. Its ten-minute clip limit belongs to this diagnostic screen. The production episode pipeline prepares longer sources using bounded chunks and persisted coverage. Native model loading cannot be interrupted midway; cancellation is checked immediately afterward.

For host JNI checks:

```sh
cmake -S app/src/main/cpp -B /tmp/distillpod-native -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/distillpod-native -j4
ctest --test-dir /tmp/distillpod-native --output-on-failure
/tmp/distillpod-native/transcriber_test /path/to/ggml-tiny.en.bin third_party/whisper.cpp/samples/jfk.wav
```

## Dependency notices

Whisper is pinned through the submodule commit. Its MIT license and bundled ggml notices are in `third_party/whisper.cpp`. No code from the noncommercial Sign in with ChatGPT DevKit was copied. The provider implements the published protocol using Nimbus JOSE JWT, OkHttp and platform cryptography. Review all application and model licenses before distribution.

Manual public-podcast validation additionally uses `PodcastBenchmarkDeviceTest` with `allowBenchmarks=true` and `FullEpisodeDeviceTest` with `allowPublicEpisode=true`. Those fixtures/network runs are excluded from normal CI. See [measured S22 results](../android/podcast-benchmark.md).

Version 0.2.1-alpha adds live progress within transcription chunks, an animated bar, active audio counter and explicit preparation phases. The saved counter remains a durable checkpoint. See [progress validation](../android/transcription-progress.md).

Version 0.3.0-alpha adds the researched library/player/queue/Saved redesign. See the [UI proposal and result](../android/ui-improvement-proposal.md), [installed build receipt](../android/evidence/ui-redesign-build.json) and [S22 UI test](../android/evidence/s22-ui-redesign-tests.txt). The test uses isolated local fixtures, restores the owner's queue/position/speed/volume and checks controls at 160% text size. The separate [locked-screen hour](../android/background-playback.md) passed on the unchanged media service.

Version 0.4.0-alpha adds artwork-led search results, show preview sheets, topic searches, local recent keyword searches with Clear, and RSS entry. Search cancellation stops the network request; obsolete responses cannot replace current results. See the [search proposal](../android/search-ui-proposal.md), [installed receipt](../android/evidence/search-ui-build.json) and [S22 tests](../android/evidence/s22-search-ui-tests.txt). Both search flows also pass at the Android system font scale of 160%.

Version 0.4.1-alpha makes the selected lavender Listening Drop the default launcher icon, with adaptive layers and Android 13+ monochrome support. It is installed and visually verified on the S22 Ultra. Build/lint, APK checksum, launch and data-preservation checks are recorded in the [icon build receipt](../android/evidence/launcher-icon-build.json). The [design proposal](../../design/icon-proposal/README.md) retains the original vector assets and local preview server instructions.
