# DistillPod

Native Android personal-alpha podcast player. The Gradle project is at the repository root.

- app/: Kotlin, Jetpack Compose, Room, Media3, WorkManager, JNI/local Whisper and optional ChatGPT integration.
- core/: Android-independent Kotlin domain code and unit tests.
- third_party/whisper.cpp/: pinned Git submodule; preserve its notices and check out recursively.
- native-tests/: host validation of the native bridge.
- scripts/: fixture preparation, emulator acceptance, instrumentation result parsing and release validation.
- docs/: user/developer guides, historical validation and release process.
- design/: original icon design source; branding/ contains the selected application identity.

Build with JDK 17 and the SDK/NDK/CMake versions in docs/development.md. Run ./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug. Native and emulator checks are documented there. Do not put credentials, keystores, personal libraries, recordings, subscription exports, model binaries or owner screenshots in Git.

Physical-device tests must preserve the owner's account, models, queue, downloads and listening state. Use an isolated emulator for fixture suites. Never uninstall or clear the owner's app to fix an update. Do not run live account requests or long playback tests without task authorization.

The version lives in gradle.properties. The release workflow signs only after unsigned builds and validation, using environment secrets and the retained personal-alpha certificate. No signing key belongs in source or build artifacts. An ordinary CI debug APK is not an upgrade distribution.

The previous Python/React server application is frozen on legacy/web, with its exact final source tagged web-legacy-final. It is not part of the active Android build.
