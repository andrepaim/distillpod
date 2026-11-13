# Firebase library sync

This directory contains the isolated protocol/security test harness and rules for basic library sync, protocol-2 queue/preferences, and protocol-3 contextual moments. The finished-content Storage extension is retained for isolated tests and disabled in application builds. The Android adapter, durable cursor/bootstrap recovery, public-feed review and account UI are implemented. See the [usage/build/test guide](../docs/android/subscription-sync.md) and the [complete plan](../docs/android/firebase-sync-telemetry-plan.md) for scope and remaining milestones.

## Local tests

Use Node 22 and Java 21. The Android Gradle build still uses Java 17. Install the locked tooling and run:

```sh
npm ci --prefix firebase --ignore-scripts --no-audit --no-fund
npm --prefix firebase run test:emulator
```

The command uses `demo-distillpod-sync` and Firestore on localhost:8085. Tests refuse to run without that emulator endpoint. No Google credentials or real project are needed. Security tests seed synthetic account control records using the rules-testing admin context; no production credentials are used.

The JavaScript commit helper is a test driver for the wire contract, not a deployed backend. `FirebaseSubscriptionCloud` implements the Android transport. Each metadata commit atomically writes a versioned library entity, an immutable change, an immutable receipt and the per-account sequence. Repeated operations return the same receipt; stale versions conflict. Rules require the companion writes, restrict entity field types/sizes and prohibit editing receipts/change history or changing account generation through the client. Public-feed approval is a client sharing decision, not an authentication mechanism; server UID rules enforce ownership.

Concurrent transactions exposed an emulator behavior where rules see a competing head update before the SDK returns a retriable transaction-precondition error. The driver retries a permission denial only after a server read proves that the active account's head advanced in the same generation. Retries are bounded; a denial with no such advancement propagates. When that denial refused the transaction's own staged writes (its reads succeeded), the driver marks it as a deterministic operation rejection. `FirebaseSubscriptionCloud.commit` implements the same bounded retry after verifying that the active account head advanced, and reports the staged-write case as `SyncOperationRejected` so the Android outbox sets that one operation aside and continues; Android emulator acceptance exercises concurrent clients and a rules-refused write.

## Cloud setup status

The owner authorized dedicated project `distillpod-native-sync` and Android app registration `dev.distillpod.android`, and enabled its Google provider. The Standard/default Firestore database is in São Paulo (`southamerica-east1`) with deletion protection. The validated library rules are deployed and SHA-1/SHA-256 signing fingerprints registered. The selected deployment uses Spark with billing disabled; billing was briefly linked during the content-storage experiment and then unlinked before full-content uploads. These November 4 cloud-setup checks did not upload a personal library, enable live telemetry or install a build on an owner phone. A later S25 in-place update retained Firebase client configuration; see the [current status and dated device evidence](../docs/android/implementation-status.md). Real Google sign-in, server sync of an empty library and cached sign-in after process restart were verified on the visible emulator. Synthetic Auth/Firestore tests run on a separate test-only Android emulator.

The CLI login and downloaded client configuration are outside the repository. Service-account keys and OAuth credentials must never be committed. Client configuration is not an authorization boundary; rules and account lifecycle validation provide access control. Configured APKs use the explicit `distillpodFirebaseConfig` build property. CI uses demo Auth/Firestore emulators. The release workflow now requires and injects the `FIREBASE_CONFIG_JSON` repository secret for distribution builds; ordinary PR/debug builds remain unconfigured. Separate live staging, App Check and broader rollout validation remain open.

Firestore rules cover basic library records, protocol-2 queue/episode/preferences records, protocol-3 contextual moments and experimental finished-content manifests. Protocol-3 rules prevent legacy writes from replacing new-format moments; the account upgrade remains explicit. Storage rules restrict immutable gzip objects to the authenticated active account generation. Enabled cloud scope excludes full transcripts, analyses, conversations and explanations. Complete account deletion, retention/compaction and independent cloud recovery backups remain later milestones. Do not deploy client/schema expansions without their corresponding rule tests and compatibility gates.

## Finished-content checks

Run `npm --prefix firebase run test:storage` with Java 21 for isolated owner/generation/schema/immutable-object tests. Android SDK acceptance now requires Auth, Firestore and Storage emulators (`--only auth,firestore,storage`). `FullContentSyncDeviceTest` restores a finished transcript, analysis, completed conversation and explanation into a separate database, while preserving the exclusion of local files and unfinished responses. This Storage experiment is disabled in application builds by `sync_content_enabled=false`, including builds with production client configuration. The owner chose Spark and local-only transcripts; project billing has been unlinked. Retain Storage tests for isolated development only, and do not provision paid services without explicit authorization.
