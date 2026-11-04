# Firebase sync foundation TDD receipt

Date: November 4, 2026. Branch: `feat/firebase-sync-telemetry`. Starting commit: `a1e8456afdab0c9d9206235951abfe9a2a65cbd6`.

This receipt preserves the initial foundation checkpoint and adds the subscription milestone below. It is not completion of the full implementation plan. At the foundation checkpoint there was no personal library upload, live SDK telemetry, signed phone upgrade or production rules deployment.

## Red and green evidence

| Behavior | Red execution | Green execution |
| --- | --- | --- |
| Protocol validation/versioning | `:core:test --tests '*SyncProtocolTest'`: 17 tests failed on explicit unimplemented behavior | All 17 passed in the complete core suite |
| Durable local change intent | `:app:testDebugUnitTest --tests '*SyncLocalStoreTest'`: 5 tests failed on explicit unimplemented behavior after correcting the test harness to close Room explicitly | All 5 passed with real Room/Robolectric persistence and transactions |
| Firestore rules/transactions | `npm --prefix firebase run test:emulator` with deny-all rules: 6 failures, 3 passes | 9 tests passed using the implementation rules and a bounded, verified competing-head retry |
| Consent/error containment | `:core:test --tests '*TelemetryTest'`: 5 tests failed on explicit unimplemented behavior | All 5 passed in the complete core suite |

The Firestore implementation initially passed 8/9 tests: simultaneous clients exposed permission-denied evaluation against a newly advanced account head. The fix preserves rules and re-reads the account from the server before a bounded retry. A denial without proof of head advancement is not retried. The successful suite retains the concurrent test and immutable-receipt/tombstone/security assertions.

Tests reject cross-account/unauthenticated reads and listings, direct entity writes without companion records, account-generation changes, history overwrite and unexpected entity fields. These are feasibility checks; final typed schema, size enforcement, full lifecycle rules and additional adversarial cases remain open.

The local outbox tests cover persistence after reopening, transaction rollback, wrong-owner/generation acknowledgement, acknowledgement preserving a later edit, and duplicate-intent rollback. Portable application mutation paths are not yet wired to this helper.

## Complete local validation

Sequential command:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Result: passed. Core: 70 tests; app: 91 tests; zero failures or skips. Debug lint and both APK builds passed. Android uses the existing Java 17/SDK 35 toolchain. The Firestore emulator uses a separate Java 21 runtime and Node 22. One earlier overlapping Gradle run produced an invalid shared-output classpath failure; the sequential run above is the accepted result. Future builds must remain sequential within this checkout.

The isolated Firestore test uses only demo project `demo-distillpod-sync` and localhost:8085. Credentials are unnecessary for tests. Node dependencies are pinned and locked. Ordinary CI now includes this suite with a separate Java 21 job; no remote CI result is claimed yet.

## Android migration validation

Schema 3 adds only the sync outbox table and owner/generation index. The existing library export allowlist excludes it. Instrumentation fixtures cover upgrades from schemas 1 and 2, preserving listening progress, saved moments and conversation content. Physical owner data is never used as the migration fixture.

The first two-version emulator run found a test-fixture issue: the schema-2 seed explicitly inserted an empty `sourcesJson` field while asserting `[]`. The seed was corrected to valid existing schema-2 content. The production migration was not changed to rewrite existing messages. The corrected run on isolated API 36.1 emulator `emulator-5556` passed both migrations in 0.428 seconds. The instrumentation result parser also passed. The S22 was not changed.

## External setup

The owner authorized a new dedicated Firebase project and completed CLI reauthentication. Created project: `distillpod-native-sync`; registered Android application: `dev.distillpod.android`. CLI credentials and downloaded app configuration remain outside Git. No billing activation, database location choice, deployed rules, production telemetry or personal data transfer occurred.

## Remaining gates at the foundation checkpoint (historical)

The feature is not ready for installation/release as cloud sync. Android Firebase integration, durable cursor/bootstrap recovery, full account isolation, user controls, native symbol upload, live telemetry, artifact transfer, account deletion, recovery backups and the complete 28-scenario matrix remain open. The local consent gate does not itself configure SDK collection; SDK adapters must prove that behavior before telemetry can ship.

## Subscription milestone receipt

Based on foundation commit `cee4a3d`; results below supersede its integration/setup status.

### Additional TDD cycles

| Behavior | Observed red | Green result |
| --- | --- | --- |
| Owner binding, local changes, coalescing and persistence | Eight initial `SubscriptionSyncStoreTest` failures with explicit unimplemented behavior | Store suite passes |
| Push/pull/bootstrap, lost acknowledgement, wrong account and sign-out race | Five initial `SubscriptionCoordinatorTest` failures with explicit unimplemented behavior | Coordinator suite passes |
| Explicit conflict choices | Two failures with resolution unimplemented; unrelated cases stayed green | Accepting cloud deletion and explicit local re-follow pass |
| Cancellation recovery | One failure: visible state remained syncing after interruption | Pending state restored; original operation receipt replayed safely |
| Offline directory follow | Failure on unimplemented follow; later failure for missing targeted refresh callback | Local save without network and per-feed refresh callback pass |
| Unknown/private-source protection | Failures for automatic baseline/manual-source upload | First-bind selection and directory provenance protect unknown sources |
| Public-feed review UI | Two device assertion failures with the empty review component | Unknown source starts unselected; only confirmed IDs passed; cancellation authorizes nothing |
| Typed cloud schema | Three failures: numeric title, oversized title and incomplete snapshot were accepted | All three rejected by rules |
| Stale receipt after another device edit | One failure: an old receipt replaced a newer remote version already pulled | Remote versions remain monotonic; latest remote state projects after acknowledgement |
| Eligible worker retries | Six unimplemented-policy failures | Signed-out/paused work stops retrying; unfinished pages continue; conflicts preserve unrelated queued work |
| Instrumentation isolation | UI test failed because automatic production configuration was enabled; generic and subscription mutation tests exposed deferred fixture intents | Runner disables automatic cloud work and intent recording before Application creation; SDK demo stores opt in explicitly |
| Existing HTTP feed compatibility | Core validation and rule acceptance failed for a public HTTP source | HTTP/HTTPS accepted while passwords/query-bearing addresses remain excluded |

### Final local validation

- Java 17/SDK 35 configured build, lint and JVM tests passed: **71 core + 119 app = 190**, no failures.
- Java 21/Node 22 isolated Firestore suite: **15 passed**, no skips.
- Existing Python release/emulator tooling suite: **11 passed**.
- Final ordinary Android acceptance on isolated API 36.1 emulator: **38 passed**, no skips, 197.780 seconds. Earlier ordinary runs had successful assertions plus two assumption skips and were correctly rejected by the strict result parser. The ordinary script now explicitly excludes SDK/restart-only classes, which remain mandatory in the separate Firebase stage.
- Targeted Auth/Firestore Android acceptance: **9 passed**, 19.675 seconds, including four schema upgrades, two review UI cases, Settings scope, production/deferred-intent isolation, and two independently authenticated SDK clients with separate databases and simultaneous edits.
- Actual process restart: seed invocation **1 passed**, 0.742 seconds; runner force-stopped the emulator package; resume invocation in the new process **1 passed**, 0.874 seconds. Cached synthetic authentication and the Room outbox survived; exactly one cloud operation was committed.

Commands are reproducible through `scripts/emulator-tests.sh`, `scripts/sync-device-tests.sh`, and `npm --prefix firebase run test:emulator`. Build configuration/fixtures stayed outside Git. Emulator SDK accounts and data used only `demo-distillpod-sync`. Tests did not sign a real Google user in, upload a personal library or install a Firebase build on an owner phone. A separately requested background task installed the saved pre-Firebase APK on the connected S25; that is outside this feature validation.

### Cloud setup and deployment

The owner enabled Google sign-in in Firebase Console. The Android web client ID was retrieved and retained SHA-1/SHA-256 fingerprints registered. Firestore API was enabled; the Standard default database was created in `southamerica-east1`, reporting `freeTier: true` with deletion protection. Validated rules compiled and deployed successfully using explicit project `distillpod-native-sync`. Billing was not enabled, and no test/owner library was written to that project.

### Remaining acceptance

Live Google account selection/token exchange and a real-phone signed upgrade are not demonstrated by emulator credentials. The alpha supports one bound account per existing library and rejects account adoption; multiple local profiles remain a later milestone. Full-data sync, cloud erasure/recovery/retention, telemetry SDK integration/ingestion, App Check, live staging and configured release automation remain open. Existing lock-screen source changes remain separate and uncommitted by this milestone. Normal app builds keep cloud access available when configured; the isolated instrumentation runner disables automatic production access even with a cached real login. Deferred fixture intents are suppressed too, so they cannot upload later after testing.
