# Build and test DistillPod

The native Gradle project lives at the repository root. Historical reports under `docs/android` retain commands and paths from the earlier `android/` subdirectory; use the root-level paths below for current work.

## Tools

- JDK 17
- Android SDK platform 35 and Build Tools 35.0.0
- NDK 27.2.12479018
- Android SDK CMake 3.22.1
- Python 3 for test-result and release validation scripts
- A development emulator for device tests

Clone recursively, or run `git submodule update --init --recursive` after cloning. The whisper.cpp revision is recorded in Git; do not replace it with an arbitrary newer checkout. Point `ANDROID_HOME` to your SDK, or create an ignored `local.properties` with `sdk.dir=/path/to/sdk`.

```sh
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'ndk;27.2.12479018' 'cmake;3.22.1'
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 -m unittest discover -s scripts -p 'test_*.py'
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Each developer/CI runner normally has its own debug key. Use signed release assets for upgrades to the personal-alpha installation.

## Native bridge checks

With a host C++ toolchain and CMake 3.22.1 or later:

```sh
cmake -S app/src/main/cpp -B /tmp/distillpod-native -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/distillpod-native --parallel 2
ctest --test-dir /tmp/distillpod-native --output-on-failure
```

These validate native inputs and lifecycle. Public-speech inference is covered by the emulator suite, using verified model fixtures. For deliberate host inference after preparing the fixtures below:

```sh
/tmp/distillpod-native/transcriber_test /tmp/distillpod-fixtures/ggml-tiny.en.bin /tmp/distillpod-fixtures/jfk.wav
```

## Isolated emulator acceptance

Start and unlock a development x86_64 emulator. The script installs fixture/main test APKs and manipulates test clips. Do not point it at the owner's daily-use phone.

```sh
scripts/prepare-fixtures.sh /tmp/distillpod-fixtures
DISTILLPOD_SERIAL=emulator-5554 scripts/emulator-tests.sh /tmp/distillpod-fixtures
```

Fixtures are fetched from the upstream public model repository and verified by SHA-256 before use; model/audio binaries are excluded from Git. The script also copies samples from the pinned Whisper submodule. Set `DISTILLPOD_ADB` and `DISTILLPOD_ADB_PORT` when using Windows ADB from WSL.

Ordinary CI excludes live-account, opt-in public-directory, and long public-podcast/battery/hour tests. Deliberate live tests require their explicit instrumentation arguments and task authorization. Never upload tokens, generated account content, personal recordings, subscription lists or owner screenshots as CI artifacts.

On disposable GitHub-hosted emulators only, the fixture script supplies Chromium's test command-line flags to bypass first-run onboarding before the real browser callback check. It verifies that the target is an emulator and removes its flags/debug-app setting on exit. This setup is never applied to an owner's phone. Lazy-list UI checks scroll the containing list before accessing rows that may not yet be composed on smaller screens.

CI also waits up to 30 seconds for user storage to become RUNNING_UNLOCKED after the emulator reports boot completion. A development phone still requires its owner to unlock it; the script does not dismiss a secure keyguard.

The browser integration check validates receipt of a real browser's callback and its state/client identity. A separate Android TCP check reads the complete HTTP response while the listener scope remains open. This tests callback delivery and response completion independently of Chrome's accessibility representation of a plain-text page.

## Firebase acceptance

The optional sync implementation on `main` has isolated Firebase protocol/security and Storage suites. Use Node 22 and Java 21 for these suites (keep Java 17 for Android):

```sh
npm ci --prefix firebase --ignore-scripts --no-audit --no-fund
npm --prefix firebase run test:emulator
npm --prefix firebase run test:storage
```

They use `demo-distillpod-sync`, with Firestore on localhost:8085 and Storage on localhost:9199, and need no cloud credentials. See [Firebase test setup](../firebase/README.md) and the [implementation plan](android/firebase-sync-telemetry-plan.md). Do not run concurrent Gradle builds against the same checkout.

The [library sync guide](android/subscription-sync.md) documents optional client configuration, Auth/Firestore/Storage Android acceptance, and the two-phase force-stop test. `scripts/sync-device-tests.sh` requires running cloud emulators and a verified Android emulator; it refuses physical-phone targets. The default build intentionally contains no production Firebase configuration. The sync runner uses ADB reverse for localhost Auth/Firestore/Storage endpoints, so acceptance does not depend on the emulator’s Wi-Fi route; it removes those forwards on exit. CI runs the ordinary device suite first and the explicit cloud acceptance afterward. `IsolatedTestRunner` disables automatic production cloud activity and deferred fixture intents; SDK cases inject demo clients explicitly.

## Workflows

`ci.yml` runs Kotlin tests, lint/APK assembly, release-tool guards, host native validation and emulator acceptance on main pushes and pull requests. Its reusable form also gates the release workflow. Actions are pinned to reviewed commit SHAs; Dependabot checks those pins and Gradle dependencies weekly.

`release.yml` accepts version tags from main history, requests the additional unsigned release build/lint, and signs only after all validation jobs pass. Signing secrets are available only in the protected alpha-release environment. The publishing job has repository-content write permission; test jobs have read permission. See [releases](releases.md).

## Deliberate device checks

Ordinary emulator acceptance uses synthetic data and public speech fixtures. Live account and long-running public-network checks require deliberate opt-in and should run only when authorized for that task:

- `LiveCaptureDeviceTest`, `LiveUnderstandingDeviceTest` and `LiveExploreDeviceTest`: instrumentation argument `allowLiveAccount=true`; `refreshLiveAccount=true` also exercises real token renewal.
- `PodcastBenchmarkDeviceTest`: `allowBenchmarks=true` for public podcast/model benchmarking.
- `FullEpisodeDeviceTest`: `allowPublicEpisode=true` for a complete public enclosure.
- `PlaybackReliabilityDeviceTest`: follow the [locked-playback protocol](android/background-playback.md), including owner-state restoration.

Select the class explicitly with Android instrumentation; do not add these opt-ins to ordinary PR runs. Preserve the owner's account, models, queue, downloads and listening state. Device and historical evidence lives in [implementation status](android/implementation-status.md); an earlier phone result does not validate every later code revision.
