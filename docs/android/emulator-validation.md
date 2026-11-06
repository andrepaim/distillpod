# Android emulator validation and playback continuation

> Historical validation report. Results and commands below apply to the stated
> revision and device. For current behavior, use the [usage guide](../getting-started.md),
> [build instructions](../development.md) and [implementation status](implementation-status.md).

Date: November 1, 2026. Branch: `feat/native-android`.

The debug app and instrumentation APK were installed and executed on the Windows-hosted `Medium_Phone_API_36.1` AVD: Android 16, x86_64, 1080 × 2400 display, approximately 2 GB guest RAM. Windows ADB port 5038 isolates the connection from Linux SDK tooling. This supersedes the earlier receipt's statement that no Android runtime testing had been performed.

## Results

| Check | Result |
| --- | --- |
| Launch, disabled AI action and missing-file recovery | Passed on emulator |
| Activity recreation | Passed on emulator |
| Real Android Keystore encrypted storage/read/delete | Passed on emulator |
| Platform RSS parser and external-entity rejection | Passed on emulator after fixing parser compatibility |
| Android system document picker and WAV import | Passed on emulator |
| Cancelled document selection preserves existing imported audio | Passed on emulator |
| Packaged JNI library with real tiny.en model and JFK audio | Passed; recognizable text and valid segment timings |
| Invalid model error translation | Passed; Java exception, no native crash |
| Imported-clip Media3 background playback | Passed after pressing Home |
| Media-session pause and original-timeline seek | Passed on emulator |
| Kotlin/Android JVM suite | 44 tests passed |
| Device runner result parser | 2 tests passed |
| Android lint | Passed; nonfatal dependency/KTX warnings remain |
| Live ChatGPT sign-in | Subsequently verified: owner authorized access and a real AI request succeeded; see [follow-up](chatgpt-validation.md) |

The instrumentation suite has **8 tests**, all passed in 24.152 seconds. The repeatable raw runner output is in [the evidence file](evidence/emulator-api36-tests.txt). The native inference test contains real model execution and is not stubbed.

## Failures found and fixed

1. The old Espresso dependency used a removed Android input API. Updating the runner/JUnit/Espresso dependencies restored Android 16 test execution.
2. RSS parsing used desktop Xerces feature flags unsupported by Android's Harmony parser. The parser now rejects DTD/entity declarations before parsing and installs an external-entity resolver that refuses resolution. Both JVM and device tests validate normal feeds and rejection.
3. Native Debug builds used unoptimized CPU inference. The same emulator/sample measured 76,533 ms before and approximately 1,900 ms after enabling `-O2` while retaining debug symbols. These are emulator observations, not phone benchmarks.
4. The document-picker test retained an accessibility object during a drawer transition and encountered stale-object errors. It now waits for idle and resolves selectors when acting. This was an automation timing defect.
5. The new playback regression initially failed because no playback action existed. A Media3 service, lifecycle-managed controller, audio focus/noisy handling, manifest permissions and playback UI were then implemented; the device regression passes.

Current debug APK SHA-256: `2b0cff24765938d915a34719edcbba2e35e04478a1446f0afde8b29132db4960`. The APK is available at `android/app/build/outputs/apk/debug/app-debug.apk`.

## Reproduction

Follow the emulator section of [the Android README](../../README.md). `android/scripts/emulator-tests.sh` builds/install both APKs and verifies the runner's completion report. ADB's shell exit code alone does not prove test success. Public ASR fixtures are supplied explicitly, and the CI job checks the model's checksum before running.

The tests install development APKs and manipulate fixture files in the app's private directory. They should not run on an installation containing personal data. No passwords or account tokens are part of test assets or this evidence.

## Remaining scope

This validates the current feasibility application and imported-clip playback. It does not establish a finished podcast library, downloads, persistent queue/progress, all AI workflows, migration, or production release readiness. Physical ARM64 phones, battery/thermal behavior and language accuracy still need representative-device testing. Live subscription access requires the owner to sign in; an emulator cannot establish that entitlement without authorization.

An accelerated API 35 emulator job is configured in GitHub Actions but has not been run remotely. The local Android 16 results above are the executed evidence.
