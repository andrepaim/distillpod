# Firebase sync foundation TDD receipt

Date: November 4, 2026. Branch: `feat/firebase-sync-telemetry`. Starting commit: `a1e8456afdab0c9d9206235951abfe9a2a65cbd6`.

This receipt covers a foundation checkpoint, not completion of the full implementation plan. No live personal library upload, live SDK telemetry, signed phone upgrade or production rules deployment is claimed.

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

## Remaining gates

The feature is not ready for installation/release as cloud sync. Android Firebase integration, durable cursor/bootstrap recovery, full account isolation, user controls, native symbol upload, live telemetry, artifact transfer, account deletion, recovery backups and the complete 28-scenario matrix remain open. The local consent gate does not itself configure SDK collection; SDK adapters must prove that behavior before telemetry can ship.
