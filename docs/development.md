# Build and test DistillPod

The native Gradle project lives at the repository root. Historical reports under `docs/android` retain commands and paths from the earlier `android/` subdirectory; use the root-level paths below for current work.

## Choose validation for the change

Follow the [risk-based validation policy](../AGENTS.md#risk-based-validation) for
each change. Record a short assessment in the plan and PR: affected behavior,
hardware/platform and upgrade/data risks, selected checks and their results,
tested revision, environment/device, and remaining limitations. Explain why
physical-device validation is or is not required; revisit the assessment when
the scope or integrated code changes.

Relevant automated checks, review, integration with current `origin/main`, and
applicable emulator acceptance remain merge requirements. Select tests for the
affected behavior and regressions using the commands below; existing CI still
runs unchanged. Pure business logic, low-risk UI organization, and content-free
reporting can use suitable automated/emulator evidence when the assessment
supports it. These examples are not blanket exemptions for an issue or area.

Physical-device checks are required for affected behavior that depends on real
hardware, OEM/platform differences, or conditions an emulator cannot reliably
establish: Bluetooth/headset/audio routing, background execution and
battery/power behavior, runtime permissions and notification/system Settings,
manufacturer-specific behavior, and relevant in-place upgrade/data-preservation
risks. Keep feature-specific physical acceptance protocols. Synthetic key
events do not prove actual accessory gestures. Emulator or mocked permission
fixtures do not establish manufacturer-specific phone behavior; real-phone
tests establish only the exact platform flows exercised, with their limitations
recorded.
A reporting change that affects background work or persisted data needs those
risks assessed too.

Use compatible, non-destructive owner-phone validation only within task
authorization. Preserve signing/upgrade compatibility and owner data; never run
emulator-only fixtures on the phone. Required failed or unavailable checks are
blockers, not waivers: record the next action and leave affected work unmerged.
No security, privacy, live-account, production-data, deployment, or release
protection is relaxed by this choice of validation.

Documentation-only changes use the existing exception: review accuracy and local
links and run `git diff --check`; no local Android build or device run is needed
unless build or app behavior also changes. Record that limitation explicitly.
CI and combined-commit verification still follow repository workflows.

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

CI validates both the default-disabled and opt-in voice build configurations. After ordinary/cloud emulator acceptance, `scripts/voice-device-tests.sh /tmp/distillpod-fixtures` builds with `distillpodVoiceEnabled=true` and runs only the synthetic headphone-key/foreground-service and isolated Keystore tests. The runner refuses physical phones; it uses no live provider credential or paid request. The voice-only device test is excluded from default-disabled acceptance.

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

`security.yml` scans fetched Git history for secrets and checks current/new
commits for personal data containers and unreviewed binary media. See
[repository security](security.md) for local checks and asset-review requirements.

`ci.yml` runs Kotlin tests, lint/APK assembly, release-tool guards, host native validation and emulator acceptance on main pushes and pull requests. Its reusable form also gates the release workflow.

Main pushes that change only Markdown, documentation screenshots/videos under
`docs/`, historical JSON/JSONL/TXT reports under `docs/android/evidence/`, or
the Manrope font license do not start Android CI. Mixed pushes still run when
GitHub evaluates any non-documentation path. The filter deliberately does not
ignore entire directories: Kotlin, Java, C++, scripts, HTML/CSS/JavaScript,
workflow YAML, Gradle files, app resources, Firebase rules/configuration, and
submodule changes remain eligible, including code added under `docs/`.
Pull requests, manual dispatch and reusable calls are not path-filtered.
The independent security workflow still scans documentation changes.

**GitHub limitation:** native path filters evaluate at most 300 changed files.
In a larger mixed push, a code file outside that set can be missed. Do not rely
on this filter for that case: use manual Android CI dispatch or split the push.
GitHub runs workflows unconditionally when a push has more than 1,000 commits
or diff generation times out. See
[GitHub path-filter limits](https://docs.github.com/en/actions/writing-workflows/workflow-syntax-for-github-actions#git-diff-comparisons).
A full-diff gate job would avoid the file limit but would itself run on every
push, including documentation-only pushes.

Actions are pinned to reviewed commit SHAs; Dependabot checks those pins and Gradle dependencies weekly.

`release.yml` accepts version tags from main history, requests the additional unsigned release build/lint, and signs only after all validation jobs pass. Signing secrets are available only in the protected alpha-release environment. The publishing job has repository-content write permission; test jobs have read permission. See [releases](releases.md).

Starting with 0.16.0-alpha.1, the signed personal-alpha distribution enables the voice preview through `-PdistillpodVoiceEnabled=true` in its release build step. Ordinary source/debug builds remain default-disabled; CI checks both configurations. Headphone voice remains off until the user configures a key, permissions and the shortcut.

## Deliberate device checks

Documentation screenshots and videos have a separate opt-in fixture on an
empty emulator. Follow [media reproduction](demos/README.md#reproduce-the-captures).
`DocumentationMediaDeviceTest` is excluded from ordinary acceptance and requires
`allowDocumentationCapture=true` when invoked directly; it must never target a
personal installation.

Ordinary emulator acceptance uses synthetic data and public speech fixtures. Live account and long-running public-network checks require deliberate opt-in and should run only when authorized for that task:

- `LiveCaptureDeviceTest`, `LiveUnderstandingDeviceTest` and `LiveExploreDeviceTest`: instrumentation argument `allowLiveAccount=true`; `refreshLiveAccount=true` also exercises real token renewal.
- `PodcastBenchmarkDeviceTest`: `allowBenchmarks=true` for public podcast/model benchmarking.
- `FullEpisodeDeviceTest`: `allowPublicEpisode=true` for a complete public enclosure.
- `PlaybackReliabilityDeviceTest`: follow the [locked-playback protocol](android/background-playback.md), including owner-state restoration.

Select the class explicitly with Android instrumentation; do not add these opt-ins to ordinary PR runs. Preserve the owner's account, models, queue, downloads and listening state. Device and historical evidence lives in [implementation status](android/implementation-status.md); an earlier phone result does not validate every later code revision.
