# Library sync alpha

Implemented on `feat/firebase-sync-telemetry`; not yet in a published APK release. This is an incremental implementation of the [complete sync and telemetry plan](firebase-sync-telemetry-plan.md).

## Use

1. Open Settings → Library sync in a configured build.
2. Choose Connect Google & sync library. Review existing feeds and select only public sources whose metadata, listening progress and saved moments you want stored in the cloud. Leave private access links unselected. New directory-result follows are recognized as public sources.
3. Continue with Google and choose the account for this library. Existing cloud subscriptions are merged/restored; ambiguous changes remain explicit conflicts.
4. Sign into the same account on another installation. An empty local library can continue through the review with no feeds selected and restore the account's subscriptions, progress, completion and saved moments.
5. Follow/unfollow offline. Changes save in Room and retry when connected. Sync status shows pending, active, paused, offline/error or conflict states. Pause/sign-out retains the local library and pending changes.

Use Add existing subscriptions to sync to approve other local feeds later. A public HTTP or HTTPS address is supported; password-bearing and query-bearing addresses stay local. Private tokens in paths cannot be inferred reliably, which is why existing/manual sources require review. Sharing a feed with a private access link may grant access to that source; select public feeds only.

This alpha supports one sync account per existing local library. Signing in with a different account is rejected. It does not move or erase the original account's data. Multiple local profiles, complete account erasure and dated cloud recovery remain open milestones. Use the original account to resume; do not uninstall the app to switch accounts.

Subscriptions, listening position, explicit completion and saved moments synchronize for approved public sources. Episode restoration includes only the metadata needed for these records, rather than the full RSS catalogue. The **Sync queue & preferences** option adds queue and portable model/language/AI-model preferences through Firestore on Spark. Transcripts, completed analyses, conversations and explanations remain local and can be transferred through Backup & Restore. Cloud Storage is disabled in builds. Downloads, models and provider credentials remain device-local. See [privacy](../privacy.md).

## Build configuration

The default source build has no production Firebase client configuration and shows “Cloud sync is unavailable in this build.” Download the Android client JSON from your Firebase project, keep it outside Git, enable Google sign-in, and register the signing certificate fingerprints. Build with Java 17:

```sh
./gradlew :app:assembleDebug -PdistillpodFirebaseConfig=/absolute/path/google-services.json
```

The configuration supplies client project/app/API identifiers and the Google web client ID. It is packaged in the APK and is not an authorization secret. Privileged credentials, OAuth codes, signing keys and Firebase CLI tokens must never enter the build or repository. Rules provide authorization. The APK's certificate must match the Google provider registration. The current release workflow does not yet inject this optional configuration; release automation is a later plan gate.

The dedicated project is `distillpod-native-sync`, with a Standard/default Firestore database in `southamerica-east1`, deletion protection enabled. Billing was briefly linked during content-storage provisioning, then unlinked at the owner’s request; billing is now disabled and the project uses Spark. Do not enable billing without explicit authorization. Google provider setup and SHA-1/SHA-256 registration are complete. Real Google sign-in, the Firebase token exchange, a server-backed sync and cached sign-in after process restart were verified on the visible emulator on November 4. No synthetic library was uploaded to the live project; synthetic SDK acceptance uses a separate emulator and demo Firebase project. Deploy the validated library rules using the explicit project argument; do not rely on an inherited CLI default.

## Local and CI acceptance

JVM/Room tests cover durable changes, safe owner binding, public-feed review/provenance, coalescing unsent changes, lost acknowledgement, interruption, stale edits, conflict choices and independent local databases. Java 21/Node 22 run the isolated Firestore rules tests; Android remains on Java 17.

```sh
npm ci --prefix firebase --ignore-scripts --no-audit --no-fund
npm --prefix firebase run test:emulator
npm --prefix firebase run test:storage
```

Build the debug and instrumentation APKs and start an isolated Android emulator. With Java 21 active for the Firebase CLI, run from the repository root:

```sh
firebase/node_modules/.bin/firebase --config firebase/firebase.json emulators:exec --only auth,firestore,storage --project demo-distillpod-sync 'env DISTILLPOD_SERIAL=emulator-5554 scripts/sync-device-tests.sh'
```

Set `DISTILLPOD_ADB` to Windows ADB when using WSL. The script verifies an emulator target. The SDK tests use independently authenticated named Firebase apps and separate Room databases on one Android emulator, not two physical phones. They cover concurrent edits, restore, sign-out/account denial and cross-user rules. A separate two-phase test force-stops the package between recording an offline intent and uploading it in a new process. Firebase emulators remain running across those phases.

CI runs ordinary Android regressions and then this targeted Auth/Firestore acceptance. The ordinary script excludes SDK/server-specific cases; the explicit cloud stage runs them with required arguments. The isolated test runner disables automatic production sync and deferred intent recording before Application creation, even in configured APKs. Demo stores opt in separately. Test fixtures use only the demo project and synthetic accounts. No live Google sign-in, production test library upload or owner-phone update is claimed by these tests. Google account selection/provider behavior still needs a live manual check before treating the sign-in path as verified on a real phone.

## Listening progress and moments

Local playback saves frequently for crash recovery. Cloud checkpoints coalesce within a session to one periodic update per 30 seconds, with additional checkpoints on meaningful playback/seek boundaries and service shutdown. Paused players stop rewriting their old position and accept a newer cloud handoff. Operation IDs already uploading remain immutable; a newer local checkpoint waits separately. Session IDs, logical sequences and the locally visible base revision preserve intentional rewinds. A remote position never seeks the current player automatically. Divergent sessions retain both candidates and offer a choice in Settings; explicitly selecting the cloud position can seek the current episode.

Played/unplayed actions are separate versioned records. A stale automatic end-of-playback event cannot undo a newer explicit mark-unplayed. Saved moments carry their original interval, excerpt, completed quote/insight, revision, fingerprint and model; local generation state and attempts never enter the cloud. Incomplete restored moments remain saved without automatically starting transcription or consuming AI allowance. Deletions use empty tombstones; stale in-flight generation cannot recreate a deleted row.

Room schema 6 adds locally visible revisions and durable session/checkpoint state. Its migration preserves library rows and the existing outbox. Remote projections preserve downloads, queue and local media fields. Private/query-bearing media addresses and oversized snapshots remain local; Backup & Restore is still required for excluded records and for the remaining portable library types.

## Extended library scope

Settings offers **Sync queue & preferences**, which upgrades the account protocol from 1 to 2 and seeds existing queue/preferences without contacting Cloud Storage. Older builds stop cloud work at this protocol gate and preserve their local library; upgrade those installations before using the extended account. A fresh installation connected to an already upgraded account joins that scope automatically.

Queue order is a versioned, deduplicated list (up to 200 public entries) with separate episode identity records. Private entries stay local and are counted in Settings. Active divergent order retains an explicit conflict; accepting cloud order preserves the current audio and applies the remaining playlist without echoing its temporary active item into the shared queue. Preferences have independent revisions and never include account identity, network/storage policies or request/job state.

### Inactive finished-content experiment

The implementation below is retained for isolated emulator tests and is disabled in application builds by `sync_content_enabled=false`. It is outside the owner-selected Spark deployment. A bucket value in the client configuration cannot enable it.

Finished content is a versioned gzip artifact (up to 8 MiB compressed / 32 MiB expanded / 100,000 segments), addressed by the SHA-256 of its expanded bytes. Cloud Storage objects are immutable and account/generation scoped. Upload succeeds before a Firestore reference can be published; retries reuse the same object and operation receipt. Resumable upload URLs remain app-private. Downloads verify size, checksum, format and source fingerprint before an atomic import. Missing parent moments defer import until metadata arrives; incompatible source revisions remain retained alternatives rather than replacing active processing. No transcription/AI request runs as a restore side effect. Completed conversation messages retain IDs and reply links, and incomplete assistant responses remain local.

“Library synced” applies to the enabled scope and supported approved sources. Settings separately counts pending content and source versions needing review. Source credentials, downloads, models, unfinished jobs and oversized/excluded records remain on the original installation. Keep manual backups for independent recovery; sync is not a dated recovery service.

Any future full-content deployment would require a billing-enabled project and explicit owner authorization. The current project has billing disabled; do not provision or deploy paid services. The client JSON must contain the actual bucket name. The required cross-service Storage rules role is `roles/firebaserules.firestoreServiceAgent` for the project's Firebase Storage service agent; it lets those rules evaluate account generation/activity in Firestore. Do not grant client users broad Cloud IAM roles or make the bucket public. Deployment must include both validated Firestore and Storage rules. Budget alerts are alerts, not a hard spending cap.
