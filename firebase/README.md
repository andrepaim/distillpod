# Firebase library sync

This directory contains the isolated protocol/security test harness and rules for basic library sync, protocol-2 queue/preferences, protocol-3 contextual moments and protocol-4 history compaction ([below](#history-compaction-protocol-4)). The finished-content Storage extension is retained for isolated tests and disabled in application builds. The Android adapter, durable cursor/bootstrap recovery, public-feed review and account UI are implemented. See the [usage/build/test guide](../docs/android/subscription-sync.md) and the [complete plan](../docs/android/firebase-sync-telemetry-plan.md) for scope and remaining milestones.

## Local tests

Use Node 22 and Java 21. The Android Gradle build still uses Java 17. Install the locked tooling and run:

```sh
npm ci --prefix firebase --ignore-scripts --no-audit --no-fund
npm --prefix firebase run test:emulator
```

To check the same contract against the rules currently deployed (for example before deploying protocol 4), export that revision's rules to a file outside the checkout and select them; protocol-4 cases are then skipped and one case checks that those rules refuse compaction:

```sh
git show <deployed-revision>:firebase/firestore.rules > /path/outside/repo/deployed.rules
DISTILLPOD_FIRESTORE_RULES=/path/outside/repo/deployed.rules DISTILLPOD_FIRESTORE_RULES_PROTOCOL=3 npm --prefix firebase run test:emulator
```

The command uses `demo-distillpod-sync` and Firestore on localhost:8085. Tests refuse to run without that emulator endpoint. No Google credentials or real project are needed. Security tests seed synthetic account control records using the rules-testing admin context; no production credentials are used.

The JavaScript commit helper is a test driver for the wire contract, not a deployed backend. `FirebaseSubscriptionCloud` implements the Android transport. Each metadata commit atomically writes a versioned library entity, an immutable change, an immutable receipt and the per-account sequence. Repeated operations return the same receipt; stale versions conflict. Rules require the companion writes, restrict entity field types/sizes and prohibit editing receipts/change history or changing account generation through the client. Public-feed approval is a client sharing decision, not an authentication mechanism; server UID rules enforce ownership.

Concurrent transactions exposed an emulator behavior where rules see a competing head update before the SDK returns a retriable transaction-precondition error. The driver retries a permission denial only after a server read proves that the active account's head advanced in the same generation. Retries are bounded; a denial with no such advancement propagates. When that denial refused the transaction's own staged writes (its reads succeeded), the driver marks it as a deterministic operation rejection. `FirebaseSubscriptionCloud.commit` implements the same bounded retry after verifying that the active account head advanced, and reports the staged-write case as `SyncOperationRejected` so the Android outbox sets that one operation aside and continues; Android emulator acceptance exercises concurrent clients and a rules-refused write.

## Cloud setup status

The owner authorized dedicated project `distillpod-native-sync` and Android app registration `dev.distillpod.android`, and enabled its Google provider. The Standard/default Firestore database is in São Paulo (`southamerica-east1`) with deletion protection. The validated library rules are deployed and SHA-1/SHA-256 signing fingerprints registered. The selected deployment uses Spark with billing disabled; billing was briefly linked during the content-storage experiment and then unlinked before full-content uploads. These November 4 cloud-setup checks did not upload a personal library, enable live telemetry or install a build on an owner phone. A later S25 in-place update retained Firebase client configuration; see the [current status and dated device evidence](../docs/android/implementation-status.md). Real Google sign-in, server sync of an empty library and cached sign-in after process restart were verified on the visible emulator. Synthetic Auth/Firestore tests run on a separate test-only Android emulator.

The CLI login and downloaded client configuration are outside the repository. Service-account keys and OAuth credentials must never be committed. Client configuration is not an authorization boundary; rules and account lifecycle validation provide access control. Configured APKs use the explicit `distillpodFirebaseConfig` build property. CI uses demo Auth/Firestore emulators. The release workflow now requires and injects the `FIREBASE_CONFIG_JSON` repository secret for distribution builds; ordinary PR/debug builds remain unconfigured. Separate live staging, App Check and broader rollout validation remain open.

Firestore rules cover basic library records, protocol-2 queue/episode/preferences records, protocol-3 contextual moments and experimental finished-content manifests. Protocol-3 rules prevent legacy writes from replacing new-format moments; the account upgrade remains explicit. Storage rules restrict immutable gzip objects to the authenticated active account generation. Enabled cloud scope excludes full transcripts, analyses, conversations and explanations. Complete account deletion and independent cloud recovery backups remain later milestones; history compaction is implemented but inactive (below). Do not deploy client/schema expansions without their corresponding rule tests and compatibility gates.

## History compaction (protocol 4)

Every commit leaves an immutable `changes/{sequence}` copy and a `receipts/{operationId}` document, so history grows without bound (#176). Protocol 4 lets the owner's installations delete old history while `entities` stay the complete current state, including tombstones:

- The account head gains `compactedThrough`. Rules allow the protocol 3 → 4 upgrade only (protocol 4 implies the record scopes of protocols 2 and 3, so earlier accounts are never widened), and let the owner raise `compactedThrough` monotonically to at most `sequence - 10000`. Entities and the head can never be deleted; commits are unchanged.
- The owner may delete `changes/{n}` with `n <= compactedThrough` and receipts whose `sequence <= compactedThrough` (an already-deleted receipt is a no-op). No one else may delete anything. Each delete reads the head once, and clients delete in batches of 10.
- Receipts written at protocol 4 store only the SHA-256 of the canonical operation signature; earlier full-text receipts stay valid and clients compare either form.
- An installation whose cursor is below `compactedThrough` rebuilds from `entities` exactly like a new installation, keeping its outbox; pending edits are then sent or surface as ordinary conflicts. A retried operation whose receipt was compacted is acknowledged when the cloud entity already holds exactly its result. Versions before this change stop at their protocol check (a sync error; the local library stays usable) and never meet a history gap.
- With compaction enabled, an installation that has completed a sync run checks at most hourly. It upgrades a protocol-3 account and raises the watermark only to history that is both more than 10,000 sequences behind the head and older than 30 days by its own observation (so nothing is compacted during the first 30 days after enabling), and never past its own cursor. It then deletes at most 200 of the oldest changes, then receipts, per run (about 4,800 deletes per installation per day at most, inside Spark's 20,000-delete quota); an interrupted run resumes with the oldest remaining documents. Compaction failures are reported to diagnostics and never change the sync outcome.

Reading and recovering from a compacted account is always enabled: without `compactedThrough` (every account under the current rules) it is a no-op. Compaction itself is inert by default. Builds enable it only with `-PdistillpodSyncCompaction=true` (default `false`, resource `sync_compaction_enabled`). The rules tests cover the upgrade, watermark bounds, owner-only deletes at and below the watermark, refused deletes above it and of entities/head, cross-user denial, hashed receipts and unaffected protocol-3 accounts. `SyncCompactionDeviceTest` in `scripts/sync-device-tests.sh` compacts an emulator account with the real SDK and recovers an offline edit and a lost acknowledgement; `DISTILLPOD_SYNC_COMPACTION=false` omits it to run the remaining acceptance against the deployed protocol-3 rules.

### Owner deployment checklist

Deployment is a separate, owner-authorized step; merging the code changes nothing for existing installations or the live project.

1. **Update installations first.** Ship and install a release containing this change (with the flag still off) on every installation of the account. Older versions keep working until the account is upgraded, and then stop at their protocol check.
2. **Validate the rules locally:** `npm --prefix firebase run test:emulator` with Java 21/Node 22, plus the deployed-rules comparison above and the Android sync acceptance.
3. **Deploy the rules** to the dedicated project only, from `firebase/`: `firebase deploy --only firestore:rules --project distillpod-native-sync`. Deploying them alone changes no account: every head stays at its current protocol without `compactedThrough`.
4. **Verify:** in the Firebase console, confirm the published ruleset matches `firebase/firestore.rules`; on an updated phone use Sync now and confirm the status returns to synced and a follow/unfollow reaches another installation.
5. **Enable compaction in a release:** add `-PdistillpodSyncCompaction=true` to the release build's lint/assemble step (as with the voice flag) and publish through the normal release checklist. The first upgrade/delete happens no earlier than 30 days after an enabled installation first runs, and only on a protocol-3 account whose head exceeds 10,000 sequences.
6. **Monitor** Firestore deletes/reads in the usage dashboard and sync failures in diagnostics.

**Rollback.** Before step 5, redeploy the previous rules (`git show <previous-revision>:firebase/firestore.rules`); no client changes are needed. After step 5, publish a release built without the flag: compaction stops and installations keep syncing and recovering. Keeping or rolling back the protocol-4 rules is both safe for ordinary sync (the earlier rules accept hashed receipts and protocol-4 heads for commits; they only refuse further compaction). Do not lower `protocol` on an upgraded account head: older versions would then read a compacted history. Deleted history cannot be restored; it holds no state that `entities` lack, and manual backups remain the independent recovery path.

## Finished-content checks

Run `npm --prefix firebase run test:storage` with Java 21 for isolated owner/generation/schema/immutable-object tests. Android SDK acceptance now requires Auth, Firestore and Storage emulators (`--only auth,firestore,storage`). `FullContentSyncDeviceTest` restores a finished transcript, analysis, completed conversation and explanation into a separate database, while preserving the exclusion of local files and unfinished responses. This Storage experiment is disabled in application builds by `sync_content_enabled=false`, including builds with production client configuration. The owner chose Spark and local-only transcripts; project billing has been unlinked. Retain Storage tests for isolated development only, and do not provision paid services without explicit authorization.
