# Restored queue acceptance coherence (#72)

The hosted red evidence at #62 head 8b1b84a reports one controller item and zero
persisted items during normal cloud restoration. It does not yet identify a
runtime defect. MediaController masks mutations before the service processes
them, so the existing test may project a queue while an earlier clear is pending.

Reuse the queue-controls fixture's existing pinned Media3 service-player lookup
as a shared androidTest helper. Preserve that fixture's actual-service removal
oracle. During the restored-queue seed phase, bind the actual ExoPlayer and require
its queue and paused state to agree with the controller and Room. Require that
coherence at every subsequent phase, including the clear before cloud projection.
Retain the original 15-second per-phase bounds and add only content-free backend
count/paused diagnostics. Preserve all cloud, marker, clear and retention checks.

Do not change PlaybackService, QueuePersistence, the serialized restored baseline
or cloud synchronization. In particular, do not add per-event database comparisons
or replace the preserved baseline. A failure after service/controller/Room
coherence must be investigated with its new evidence, not waived or retried
unchanged. The stale-clear explanation remains a hypothesis until validated.

Review the exact diff, compile/lint/build default and voice variants, run applicable
Python/security/data checks and require all five hosted checks. Merge normally,
then verify merged-main CI before closing this scoped fixture issue. The code is
limited to guarded disposable-emulator tests and their existing reflection helper;
no runtime, schema, release or physical-device behavior changes. Feature S22
acceptance remains required separately.
