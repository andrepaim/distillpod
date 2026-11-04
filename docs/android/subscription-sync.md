# Subscription sync alpha

Implemented on `feat/firebase-sync-telemetry`; not yet in a published APK release. This is the first usable scope of the [complete sync and telemetry plan](firebase-sync-telemetry-plan.md).

## Use

1. Open Settings → Subscription sync in a configured build.
2. Choose Connect Google & sync subscriptions. Review existing feeds and select only public sources you want stored in the cloud. Leave private access links unselected. New directory-result follows are recognized as public sources.
3. Continue with Google and choose the account for this library. Existing cloud subscriptions are merged/restored; ambiguous changes remain explicit conflicts.
4. Sign into the same account on another installation. An empty local library can continue through the review with no feeds selected and restore the account's subscriptions.
5. Follow/unfollow offline. Changes save in Room and retry when connected. Sync status shows pending, active, paused, offline/error or conflict states. Pause/sign-out retains the local library and pending changes.

Use Add existing subscriptions to sync to approve other local feeds later. A public HTTP or HTTPS address is supported; password-bearing and query-bearing addresses stay local. Private tokens in paths cannot be inferred reliably, which is why existing/manual sources require review. Sharing a feed with a private access link may grant access to that source; select public feeds only.

This alpha supports one sync account per existing local library. Signing in with a different account is rejected. It does not move or erase the original account's data. Multiple local profiles, complete account erasure and dated cloud recovery remain open milestones. Use the original account to resume; do not uninstall the app to switch accounts.

Only subscriptions synchronize here. Listening position, saved moments, transcripts, conversations and queue still require Backup & Restore. Downloads, models and provider credentials remain device-local. See [privacy](../privacy.md).

## Build configuration

The default source build has no production Firebase client configuration and shows “Cloud sync is unavailable in this build.” Download the Android client JSON from your Firebase project, keep it outside Git, enable Google sign-in, and register the signing certificate fingerprints. Build with Java 17:

```sh
./gradlew :app:assembleDebug -PdistillpodFirebaseConfig=/absolute/path/google-services.json
```

The configuration supplies client project/app/API identifiers and the Google web client ID. It is packaged in the APK and is not an authorization secret. Privileged credentials, OAuth codes, signing keys and Firebase CLI tokens must never enter the build or repository. Rules provide authorization. The APK's certificate must match the Google provider registration. The current release workflow does not yet inject this optional configuration; release automation is a later plan gate.

The dedicated project is `distillpod-native-sync`, with a Standard/default Firestore database in `southamerica-east1`, deletion protection enabled and billing not activated. Google provider setup and SHA-1/SHA-256 registration are complete. Deploy the validated subscription rules using the explicit project argument; do not rely on an inherited CLI default.

## Local and CI acceptance

JVM/Room tests cover durable changes, safe owner binding, public-feed review/provenance, coalescing unsent changes, lost acknowledgement, interruption, stale edits, conflict choices and independent local databases. Java 21/Node 22 run the isolated Firestore rules tests; Android remains on Java 17.

```sh
npm ci --prefix firebase --ignore-scripts --no-audit --no-fund
npm --prefix firebase run test:emulator
```

Build the debug and instrumentation APKs and start an isolated Android emulator. With Java 21 active for the Firebase CLI, run from the repository root:

```sh
firebase/node_modules/.bin/firebase --config firebase/firebase.json emulators:exec --only auth,firestore --project demo-distillpod-sync 'env DISTILLPOD_SERIAL=emulator-5554 scripts/sync-device-tests.sh'
```

Set `DISTILLPOD_ADB` to Windows ADB when using WSL. The script verifies an emulator target. The SDK tests use independently authenticated named Firebase apps and separate Room databases on one Android emulator, not two physical phones. They cover concurrent edits, restore, sign-out/account denial and cross-user rules. A separate two-phase test force-stops the package between recording an offline intent and uploading it in a new process. Firebase emulators remain running across those phases.

CI runs ordinary Android regressions and then this targeted Auth/Firestore acceptance. The ordinary script excludes SDK/server-specific cases; the explicit cloud stage runs them with required arguments. The isolated test runner disables automatic production sync and deferred intent recording before Application creation, even in configured APKs. Demo stores opt in separately. Test fixtures use only the demo project and synthetic accounts. No live Google sign-in, production test library upload or owner-phone update is claimed by these tests. Google account selection/provider behavior still needs a live manual check before treating the sign-in path as verified on a real phone.
