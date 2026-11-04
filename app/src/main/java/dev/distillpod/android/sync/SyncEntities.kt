package dev.distillpod.android.sync

import androidx.room.*

@Entity(tableName="sync_outbox", indices=[Index(value=["uid","generation"])])
data class SyncOutboxEntity(
    @PrimaryKey val operationId: String,
    val uid: String, val generation: String, val entityKey: String, val baseVersion: Long,
    val payload: String, val deleted: Boolean,
)
@Dao
interface SyncDao {
    @Insert suspend fun enqueue(value: SyncOutboxEntity)
    @Query("SELECT operationId FROM sync_outbox WHERE uid=:uid AND generation=:generation ORDER BY rowid")
    suspend fun pending(uid: String, generation: String): List<String>
    @Query("DELETE FROM sync_outbox WHERE uid=:uid AND generation=:generation AND operationId=:id")
    suspend fun acknowledge(uid: String, generation: String, id: String)
}
