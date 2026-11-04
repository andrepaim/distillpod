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

## Portable core-library increment — November 4

Starting source: `c2afa83d27c95426e1c13ae8f3a7b7382f178da9` on `feat/firebase-sync-telemetry`. The previously dirty lock-screen controls, version/release notes and their tests/evidence remain separate. Playback sync hunks were staged independently of those controls.

### Red/green cycles

| Behavior | Observed red | Green |
| --- | --- | --- |
| Portable progress/moment persistence and transport | `PortableLibrarySyncTest`: 7 behavioral failures out of 9 with a compiling local-only persistence seam | Durable outbox, restoration, rewind/session conflicts, completion and privacy cases pass |
| Actual capture source and completed insight | `CaptureRepositoryTest`: 1 failure out of 7 because no moment operation was recorded | Capture/hydration/completion record portable snapshots atomically with the source row |
| Server support for new record types | Node suite: 3 failures out of 20 because progress, moment and completion writes were denied | All 20 rule cases pass; malformed identity/types, job states, private media links and stale revisions rejected |
| Incoming active-session divergence | 1 failure out of 11: sync reported success while a newer remote position remained hidden | Durable local candidate and explicit conflict retained; playback position preserved |
| Paused handoff | 1 failure out of 12: a paused session manufactured a conflict for a newer cloud position | Paused snapshots no longer overwrite remote handoff; explicit pause/seek boundaries still save |
| Service stop checkpoint | 1 failure out of 13: latest local position had no cloud intent on stop | Final checkpoint queues immediately, releases active protection and follows the last serialized service write |

Additional regression cases cover an edit during server acknowledgement, outer-transaction rollback, a newer pulled moment revision not becoming the base of an unseen local edit, separate explicit-unplayed versus stale automatic completion, instrumentation suppression, and an actual AI response delivered after moment deletion. No fake crash/native job state is restored from cloud moment content.

Local commands use JDK 17 and the existing Android SDK for Gradle; JDK 21/Node 22 for the isolated Firebase emulator. Raw synthetic logs are in `/tmp/distillpod-portable-*.log`; client configuration stays outside Git.

### Live Google acceptance

The visible `Medium_Phone_API_36.1` emulator had no Google accounts before this check. Only its disposable fixture app data was cleared. The owner entered Google credentials directly in Google's UI and confirmed completion. DistillPod then showed its signed-in account and “Subscriptions synced”. Force-stop/relaunch preserved the account, completed server sync and displayed the sign-out control. Only an empty account library was used; no synthetic library was uploaded to the live project. The phone was not used for these checks.

A second, fresh test-only AVD (`DistillPod_Sync_Test`, emulator-5558) runs ordinary and synthetic cloud tests independently. Its named SDK clients use only `demo-distillpod-sync`. The Google sign-in emulator (emulator-5556) never receives synthetic instrumentation or fixtures after sign-in.

### Validation checkpoint

- JVM/core/Room: **71 core + 134 app = 205 tests**, no failures; lint and debug/test APK builds passed.
- Firestore rules: **20 passed**, no skips.
- First expanded Android SDK run: **10 passed**, including schemas 1–5 upgrading to 6, public review/Settings/isolation and the two independent SDK clients. The SDK scenario restores progress/completion/moments without downloads or transcript jobs, preserves an offline rewind versus another active session, applies an explicit conflict choice and synchronizes moment deletion. Actual process restart seed/resume invocations also passed individually.
- First ordinary Android run: **39 passed**, no skips, 234.551 seconds. This preceded the final paused/stop handoff refinements and the extra restored-moment UI case; final verification is recorded below when complete.

This increment remains partial against the full plan: queue/preferences/artifacts/conversations, recoverable profiles, account erasure/backups/retention, telemetry SDK delivery, App Check, live performance/cost measurement and configured release automation are still open. It is not a new published release or a Firebase installation on the physical phone.

### Final increment verification

- Final ordinary Android acceptance: **40 passed**, no skips, 190.049 seconds. This includes paused/stop handoff refinements and the restored-incomplete-moment UI case, which stays saved with an explicit preparation action and no transcript job.
- Final Auth/Firestore Android acceptance: **11 passed**, no skips. Schema 3–5 fixtures retain an existing pending operation; schema 4–5 fixtures also migrate their visible remote base to schema 6. Two-client progress/moment restore/conflict/delete and isolation remain green. Actual force-stop seed/resume invocations each pass separately.
- JVM: **205 passed** (71 core, 134 app). Lint and APK/test-APK builds pass. Firestore rules: **20 passed**, no skips.

The local ordinary suite includes the pre-existing uncommitted lock-screen test; committed-source CI intentionally does not include that separate test. No Firebase release was published.

## Extended portable-library implementation — November 4

Starting source: `e7dd174`. This increment extends the account protocol to 2 and Room to schema 7, while retaining the existing uncommitted lock-screen changes separately.

Observed red/green cycles:

| Behavior | Observed red | Result |
| --- | --- | --- |
| Queue/preference wire contract | 2 failures / 5 core cases: valid new payloads were rejected | Strict ordered queue and per-key preference contracts pass |
| Durable queue/preference writes and restoration | 4 failures / 4 Room cases with the compiling local-only seam | Queue identities/order, coalescing, independent preferences and private locator exclusion pass |
| Server record support | 2 failures / 24 rules cases for valid queue/episode/preference writes | Typed new records pass and forbidden payloads remain denied |
| Compressed content format | 4 failures / 4 core cases with an identity-codec seam | Gzip, SHA-256, declared-size/format validation and expansion-bomb rejection pass |
| Finished content serialization | 2 failures / 4 Room cases with an empty snapshot seam | Ready transcript/provenance and completed text serialize; partial AI and private links stay local |
| Fresh content restoration | 1 failure / 6 cases with restore returning false | Atomic import creates finished content without jobs; different fingerprints remain protected |
| Artifact manifests/protocol upgrade | Valid manifests and owner protocol upgrade rejected by old rules | Manifest schema and monotonic owner-only upgrade pass |
| Old-client compatibility | New-type write incorrectly succeeded on protocol 1 | New record types require protocol 2; old clients stop before consuming unsupported records |
| Immutable Storage objects | An overwrite unexpectedly succeeded | Explicit `resource == null` protects creation; overwrite/delete stay denied |
| Private queue acknowledgement | 1 failure / 5 cases: own ack did not advance the public subset's base | Private entries retain their place and later public edits avoid false conflicts |
| Corrupted upload staging | 1 failure / 3 cases: damaged gzip blocked the pending operation | Staging rebuilds from local completed content without changing the pending operation ID |

Additional checks verify separate upload-before-reference publication, a connection interrupted after object acceptance, stable retry identities, fresh independent database restoration, pending parent references, account/generation-scoped upload-session recovery, and explicit concurrent queue choice preserving the player session. Storage test objects use unique synthetic hashes per case; clearing the emulator alone did not reliably remove the previous object's rule-visible identity. This preserves the immutable-object assertions rather than weakening them.

Validation before final deployment:

- 82 core + 149 app JVM tests passed before the final staging-recovery regression was added; final counts are recorded after the complete run.
- 27 Firestore rules tests and 4 Storage rules tests passed, with no skips.
- Real Auth/Firestore/Storage Android SDK acceptance: 13 tests passed, followed by separate actual force-stop seed/resume invocations. Two named clients and Room databases restored queue, portable preference, transcript, summary, completed conversation and explanation without computation or downloaded audio.
- Ordinary Android acceptance: 41 passed, 196.673 seconds, before the final queue/playback regression. The additional queue/playback device test passed separately in 2.462 seconds: current audio/position continued, while the temporary active item did not echo into the shared queue.

The full scope probes the active account's Storage prefix before upgrading the protocol. No bucket/missing-rules failure can upgrade the account first. Pending uploads/restores and incompatible source alternatives have separate UI status; received content waiting on moment parents is retried after metadata projection. App-private staging and resumable upload URLs are excluded from library exports. No telemetry collection is enabled by these changes.

A read-only infrastructure check found billing disabled and zero Storage buckets. One open billing account is available. Live provisioning/deployment and a phone upgrade follow only after the code validation checkpoint; deployment evidence is recorded separately. The existing phone remains on 0.8.0-alpha.1 during this implementation.

### Final extended-scope local gate

- 82 core + 150 app JVM tests: **232 passed**; debug lint/APKs passed.
- Final ordinary isolated Android acceptance: **42 passed**, no skips, 191.029 seconds (includes the separate pre-existing lock-screen test).
- Real Auth/Firestore/Storage targeted acceptance: **13 passed**, plus both seed/resume invocations after an actual process force-stop.
- Firestore: **27 passed**. Storage: **4 passed**. Python release/emulator tools: **11 passed**.
- Existing billing account linked to this project and a USD 5 monthly alert budget created. Bucket provisioning is still being resolved; rules and phone rollout remain gated on a working private bucket.


## Spark-only rollback — November 4

The owner explicitly declined Blaze and selected local-only transcripts. The project billing account was unlinked; the Cloud Billing API confirmed `billingEnabled=false`, with no billing account linked. A subsequent read-only API check also confirmed billing disabled. A private empty content bucket had been provisioned and rules deployed, but no production full-content uploads or protocol upgrade occurred. The owner's phone remains on its existing 0.8.0-alpha.1 basic-sync installation with its library intact. No new release was published.

Builds now set `sync_content_enabled=false`, regardless of a configured bucket, preventing construction of the production Storage adapter, artifact capture, upload or download. Queue/preferences activation uses Firestore independently of Storage. Settings describes the actual scope and directs text transfer to manual Backup & Restore. The experimental artifact code and demo tests remain inactive development work; full-content production deployment is cancelled. Billing must not be enabled without explicit authorization.

Validation: app JVM tests, debug APK/test-APK builds and debug lint pass. The `sparkBuildKeepsContentStorageDisabled` instrumentation regression verifies the build resource and runtime content gate. All four targeted Settings/isolation/device regressions passed on the isolated test emulator, including this new guard. The owner phone and live Google-account emulator were not used.
