Claiming #60's playback audio ownership increment. Main is c35904c1a051e39d3cf819b9ae8162e12f32bdcd. Branch fix/60-playback-audio-ownership in /home/andrepaim/src/distillpod-issue60-playback-owner, no PR yet. The transcription reader increment is merged and verified. Other issue and release worktrees remain owned separately.

Behavior and design:
- Keep a revocable selection for each managed local audio copy. Prepare it off the player looper while holding MediaRepository's existing episode transfer lock. Preserve a remote fallback URI in an in-process MediaItem tag.
- A synchronous ForwardingPlayer claims queue ownership before forwarding every playlist mutation, including all set/add/replace overloads and nested callbacks. When deletion revoked a prepared copy first, admission substitutes its fallback. Reconcile owners against the actual resulting playlist after mutation, including exceptions, duplicates and removals.
- Local Media3 data sources acquire a separate reader lease through close. If deletion wins before an old loader opens, open the fallback source on the loader thread. Opening/closing failures release ownership.
- Deletion reserves all relevant paths atomically against queue and reader leases, invalidates prepared selections, then performs file IO outside the ownership monitor. Keep existing Room, queue and processing checks. Orphan cleanup skips reserved/owned copies.
- Route own-package and external session selections, resumption, startup restore, cloud application and local source refresh through the same selection preparation. Preserve video intent and diagnostic clips. Keep the serialized restored-queue baseline and user progress ordering.

Acceptance and TDD:
- Controlled red/green tests for both admission/deletion orders, stale selections, independent files, duplicate/moved/replaced items, reentrant mutations, partial failure, source open failure and close/cancellation.
- Real Room/file checks for expected deletion blocks and retry after release, orphan cleanup, and rollback/error distinction.
- Test remote fallback URI, metadata and video intent, plus every session resolution route. Use actual ExoPlayer and MediaSession integration where applicable.
- Both local default/voice tests, lint and APK pairs; all hosted emulator phases; isolated offline S22 fixtures for real file playback, queue changes and cleanup ordering. Record immutable hashes and targets before installation and preserve production data. The phone protocol covers synthetic playback and file lifetime, with OEM scheduling limitations recorded.
- Review and current-main integration, normal PR merge and exact merged-main CI. No release metadata or publication action.

Risks include deleting a queued/read copy, leaked leases preventing cleanup, queue ordering changes, losing video intent, blocking the player looper and fallback configuration mismatch. A short registry monitor covers counters only. Playback and IO scheduling remain on their current loopers. #60 stays open for bulk controls, transcript suppression and retention/scheduler work after this prerequisite.
