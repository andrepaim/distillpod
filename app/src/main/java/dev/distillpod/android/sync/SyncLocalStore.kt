package dev.distillpod.android.sync

import androidx.room.withTransaction
import org.json.JSONObject
import dev.distillpod.core.sync.SyncProtocol
import dev.distillpod.android.data.AppDatabase
import dev.distillpod.core.sync.SyncMutation

class SyncLocalStore(private val db: AppDatabase) {
    suspend fun record(mutation: SyncMutation, writeLibrary: suspend () -> Unit) {
        SyncProtocol.validate(mutation)
        db.withTransaction {
            writeLibrary()
            if(SubscriptionCloudAccess.instrumentation)return@withTransaction
            db.sync().enqueue(SyncOutboxEntity(mutation.id, mutation.owner.uid, mutation.owner.generation,
                mutation.key, mutation.baseVersion, JSONObject(mutation.payload.toSortedMap()).toString(), mutation.deleted))
        }
    }
    suspend fun pending(uid: String, generation: String): List<String> = db.sync().pending(uid, generation)
    suspend fun acknowledge(uid: String, generation: String, operationId: String) { if(!SubscriptionCloudAccess.instrumentation)db.sync().acknowledge(uid, generation, operationId) }
}
