package dev.distillpod.android.sync

import dev.distillpod.core.sync.*

// Transport-independent steps of a commit, shared by the Firebase transport and its tests.

/** The operation's payload, validated as the protocol requires before any cloud write; an invalid stored operation never becomes valid. */
internal fun commitPayload(operation:SyncOutboxEntity):Map<String,String> {
    val payload=SubscriptionSyncStore.decode(operation.payload)
    try {
        SyncProtocol.validate(SyncMutation(operation.operationId,SyncOwner(operation.uid,operation.generation),operation.entityKey,operation.baseVersion,payload,operation.deleted))
    } catch(invalid:IllegalArgumentException) { throw SyncOperationRejected(invalid) }
    return payload
}

/**
 * Uploads an operation's content. An attempt that reached upload before may already be published:
 * its receipt then completes the commit, so a refusal of the content stands only without one.
 */
internal suspend fun uploadContent(operation:SyncOutboxEntity,upload:suspend ()->Unit,published:suspend ()->Boolean) {
    try { upload() }
    catch(refused:SyncOperationRejected) { if(operation.state!="uploading" || !published())throw refused }
}
