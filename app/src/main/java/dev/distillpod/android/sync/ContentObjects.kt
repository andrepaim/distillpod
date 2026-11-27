package dev.distillpod.android.sync

import dev.distillpod.core.sync.ContentArtifact
import dev.distillpod.core.sync.SyncOwner

/** Immutable content-addressed objects of a sync account; the Firebase Storage transport exists only in a build with sync. */
interface ContentObjects {
    suspend fun probe(owner:SyncOwner) {}
    suspend fun put(owner:SyncOwner,artifact:ContentArtifact)
    suspend fun get(owner:SyncOwner,hash:String,expandedBytes:Long):ContentArtifact
}
