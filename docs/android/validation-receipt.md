# Android feasibility validation receipt

Date: November 1, 2026. Branch: `feat/native-android`.

## Verified locally

| Check | Result |
| --- | --- |
| Kotlin core tests | 29 passed; zero failures/errors/skips |
| Android JVM tests | 15 passed under Robolectric; zero failures/errors/skips |
| Android lint | Passed with warnings about available dependency updates and optional KTX usage |
| Debug APK assembly | Passed; ARM64 and x86_64 JNI libraries verified inside APK |
| Native Release input tests | Passed through CTest |
| Real native Whisper inference | Passed on Linux using tiny.en and upstream JFK audio; checked recognizable transcript content and timed output |
| Existing backend regression suite | 90 passed; Whisper import stubbed as in CI |
| Repository checks | Diff whitespace and documentation links passed |

Android command, from `android/` with JDK 17 and the pinned SDK/NDK tools installed:

```sh
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Native commands and model requirements are in [the Android README](../../android/README.md). The smoke test checks real content with explicit runtime checks that remain active in Release builds.

Whisper submodule commit: `482279b8a18855ba741d7b88c30fcb3874691f59`.

Host test model SHA-256: `921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f`.

Development APK SHA-256: `bd0ea036cfeca94bca4a379581737c70a5504b19bb7fe51b444531b6a6315dc6`.

The debug APK is generated at `android/app/build/outputs/apk/debug/app-debug.apk` and is intentionally excluded from git. Rebuilding with another local debug signing key can change its checksum.

## TDD regressions observed and resolved

- Missing domain/native/provider/persistence/authentication/UI APIs failed compilation before implementation.
- Revoked refresh credentials remained saved: a regression test failed, then passed after credential invalidation was implemented.
- Backup exclusion resource was absent: a regression test failed before explicit cloud/device-transfer exclusions were added.
- Existing server chat and research routes returned unauthenticated data: two regression tests failed, then passed after middleware protection was extended.
- The existing feed test asserted that descriptions were absent even though the latest feature adds descriptions. It now checks populated and null descriptions against seeded data.
- The initial UI test expected offscreen content to be visible. It was corrected to scroll to content in the intentionally scrollable screen; this was a test expectation error.

## Not verified

No physical Android device or live ChatGPT account was used. Robolectric does not establish actual browser callback behavior, Keystore behavior, JNI execution on Android, background performance, battery use, refresh rotation against OpenAI, plan entitlement or real subscription-backed inference. Linux inference does not establish phone inference speed or transcription accuracy on English/Portuguese podcasts.

Remote GitHub Actions has been configured but has not run. The complete 82-task application plan is not implemented. See [implementation status](implementation-status.md) for delivered scope and remaining tasks. The current APK is a feasibility harness, not a release-ready podcast player.
