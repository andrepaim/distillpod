# Firebase library sync

This directory contains the isolated protocol/security test harness and deployed rules for basic library sync and the protocol-2 queue/preferences/finished-content extension. The Android adapter, durable cursor/bootstrap recovery, public-feed review and account UI are implemented. See the [usage/build/test guide](../docs/android/subscription-sync.md) and the [complete plan](../docs/android/firebase-sync-telemetry-plan.md) for scope and remaining milestones.

## Local tests

Use Node 22 and Java 21. The Android Gradle build still uses Java 17. Install the locked tooling and run:

```sh
npm ci --prefix firebase --ignore-scripts --no-audit --no-fund
npm --prefix firebase run test:emulator
```

The command uses `demo-distillpod-sync` and Firestore on localhost:8085. Tests refuse to run without that emulator endpoint. No Google credentials or real project are needed. Security tests seed synthetic account control records using the rules-testing admin context; no production credentials are used.

The JavaScript commit helper is a test driver for the wire contract, not a deployed backend. `FirebaseSubscriptionCloud` implements the Android transport. Each metadata commit atomically writes a versioned library entity, an immutable change, an immutable receipt and the per-account sequence. Repeated operations return the same receipt; stale versions conflict. Rules require the companion writes, restrict entity field types/sizes and prohibit editing receipts/change history or changing account generation through the client. Public-feed approval is a client sharing decision, not an authentication mechanism; server UID rules enforce ownership.

Concurrent transactions exposed an emulator behavior where rules see a competing head update before the SDK returns a retriable transaction-precondition error. The driver retries a permission denial only after a server read proves that the active account's head advanced in the same generation. Retries are bounded; a denial with no such advancement propagates. The Android transport must implement and test this distinction before integration.

## Cloud setup status

The owner authorized dedicated project `distillpod-native-sync` and Android app registration `dev.distillpod.android`, and enabled its Google provider. The Standard/default Firestore database is in São Paulo (`southamerica-east1`) with deletion protection. The validated library rules are deployed and SHA-1/SHA-256 signing fingerprints registered. Billing has not been enabled. No personal library, live telemetry or Firebase build installation on an owner phone has been performed by this work. Real Google sign-in, server sync of an empty library and cached sign-in after process restart were verified on the visible emulator. Synthetic Auth/Firestore tests run on a separate test-only Android emulator.

The CLI login and downloaded client configuration are outside the repository. Service-account keys and OAuth credentials must never be committed. Client configuration is not an authorization boundary; rules and account lifecycle validation provide access control. Configured APKs use the explicit `distillpodFirebaseConfig` build property. CI uses demo Auth/Firestore emulators. Separate live staging, App Check and release configuration injection remain required before broader rollout.

Firestore rules cover basic library records, protocol-2 queue/episode/preferences records and finished-content manifests. Storage rules restrict immutable gzip objects to the authenticated active account generation. Other library entities, complete account deletion, retention/compaction and independent recovery backups remain later milestones. Do not deploy client/schema expansions without their corresponding rule tests and compatibility gates.

## Finished-content checks

Run `npm --prefix firebase run test:storage` with Java 21 for isolated owner/generation/schema/immutable-object tests. Android SDK acceptance now requires Auth, Firestore and Storage emulators (`--only auth,firestore,storage`). `FullContentSyncDeviceTest` restores a finished transcript, analysis, completed conversation and explanation into a separate database, while preserving the exclusion of local files and unfinished responses. This Storage experiment is disabled in application builds by `sync_content_enabled=false`, including builds with production client configuration. The owner chose Spark and local-only transcripts; project billing has been unlinked. Retain Storage tests for isolated development only, and do not provision paid services without explicit authorization.
