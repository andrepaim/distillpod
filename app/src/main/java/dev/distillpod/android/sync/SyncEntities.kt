package dev.distillpod.android.sync

import androidx.room.*

@Entity(tableName="sync_outbox", indices=[Index(value=["uid","generation"])])
data class SyncOutboxEntity(
    @PrimaryKey val operationId: String,
    val uid: String, val generation: String, val entityKey: String, val baseVersion: Long,
    val payload: String, val deleted: Boolean,
    @ColumnInfo(defaultValue="'queued'") val state: String = "queued",
    @ColumnInfo(defaultValue="0") val baseline: Boolean = false,
)
@Dao
interface SyncDao {
    @Upsert suspend fun putArtifact(value:SyncArtifactEntity)
    @Query("SELECT * FROM sync_artifacts WHERE uid=:uid AND generation=:generation AND hash=:hash") suspend fun artifact(uid:String,generation:String,hash:String):SyncArtifactEntity?
    @Query("SELECT * FROM sync_artifacts WHERE uid=:uid AND generation=:generation") suspend fun artifacts(uid:String,generation:String):List<SyncArtifactEntity>
    @Query("SELECT * FROM sync_artifacts") fun observeArtifacts():kotlinx.coroutines.flow.Flow<List<SyncArtifactEntity>>

    @Upsert suspend fun putPreference(value:SyncPreferenceEntity)
    @Query("SELECT * FROM sync_preferences") fun observePreferences():kotlinx.coroutines.flow.Flow<List<SyncPreferenceEntity>>
    @Query("SELECT * FROM sync_preferences") suspend fun preferences():List<SyncPreferenceEntity>

    @Query("SELECT * FROM sync_views WHERE entityKey=:key") suspend fun view(key:String):SyncViewEntity?
    @Query("SELECT * FROM sync_views WHERE entityKey=:key") fun observeView(key:String):kotlinx.coroutines.flow.Flow<SyncViewEntity?>
    @Upsert suspend fun putView(value:SyncViewEntity)
    @Query("UPDATE sync_views SET active=0") suspend fun releasePlaybackViews()

    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun approve(value:SyncPublicFeedEntity)
    @Query("DELETE FROM sync_public_feeds") suspend fun clearApprovals()
    @Query("SELECT * FROM sync_public_feeds WHERE podcastId=:id") suspend fun approved(id:String):SyncPublicFeedEntity?
    @Query("SELECT * FROM sync_public_feeds") fun observeApprovals():kotlinx.coroutines.flow.Flow<List<SyncPublicFeedEntity>>

    @Query("SELECT * FROM sync_binding WHERE id=0") suspend fun binding(): SyncBindingEntity?
    @Query("SELECT * FROM sync_binding WHERE id=0") fun observeBinding(): kotlinx.coroutines.flow.Flow<SyncBindingEntity?>
    @Upsert suspend fun putBinding(value: SyncBindingEntity)
    @Query("SELECT * FROM sync_outbox WHERE uid=:uid AND generation=:generation ORDER BY rowid LIMIT 100") suspend fun operations(uid: String, generation: String): List<SyncOutboxEntity>
    @Query("SELECT * FROM sync_outbox WHERE uid=:uid AND generation=:generation AND entityKey=:key ORDER BY rowid") suspend fun forKey(uid:String,generation:String,key:String): List<SyncOutboxEntity>
    @Query("SELECT * FROM sync_outbox WHERE uid=:uid AND generation=:generation AND state NOT IN ('conflict','upgrade_required') ORDER BY rowid LIMIT 1") suspend fun nextOperation(uid:String,generation:String): SyncOutboxEntity?
    @Query("UPDATE sync_outbox SET state='queued' WHERE uid=:uid AND generation=:generation AND state='upgrade_required'") suspend fun resumeContextualOperations(uid: String, generation: String)
    @Query("SELECT * FROM sync_outbox WHERE state='conflict' ORDER BY rowid LIMIT 20") fun observeConflicts(): kotlinx.coroutines.flow.Flow<List<SyncOutboxEntity>>
    @Query("SELECT COUNT(*) FROM sync_outbox") fun observeCount(): kotlinx.coroutines.flow.Flow<Int>
    @Query("SELECT COUNT(*) FROM sync_outbox") suspend fun count(): Int
    @Query("SELECT * FROM sync_outbox WHERE uid=:uid AND generation=:generation") fun observeOperations(uid:String,generation:String): kotlinx.coroutines.flow.Flow<List<SyncOutboxEntity>>
    @Query("SELECT * FROM sync_remote WHERE uid=:uid AND generation=:generation AND entityKey=:key") suspend fun remote(uid:String,generation:String,key:String): SyncRemoteEntity?
    @Query("SELECT * FROM sync_remote WHERE uid=:uid AND generation=:generation AND entityKey=:key") fun observeRemote(uid:String,generation:String,key:String):kotlinx.coroutines.flow.Flow<SyncRemoteEntity?>
    @Upsert suspend fun putRemote(value: SyncRemoteEntity)
    @Query("DELETE FROM sync_outbox WHERE uid=:uid AND generation=:generation AND entityKey=:key AND state='queued'") suspend fun removeQueued(uid:String,generation:String,key:String)
    @Query("UPDATE sync_outbox SET state=:state WHERE operationId=:id") suspend fun state(id:String,state:String)
    @Query("UPDATE sync_outbox SET baseVersion=:version WHERE uid=:uid AND generation=:generation AND entityKey=:key AND baseVersion=:oldVersion AND state='queued'") suspend fun advanceOwnBase(uid:String,generation:String,key:String,oldVersion:Long,version:Long)

    @Insert suspend fun enqueue(value: SyncOutboxEntity)
    @Query("SELECT operationId FROM sync_outbox WHERE uid=:uid AND generation=:generation ORDER BY rowid")
    suspend fun pending(uid: String, generation: String): List<String>
    @Query("DELETE FROM sync_outbox WHERE uid=:uid AND generation=:generation AND operationId=:id")
    suspend fun acknowledge(uid: String, generation: String, id: String)
}

@Entity(tableName="sync_binding")
data class SyncBindingEntity(
    @PrimaryKey val id: Int = 0,
    val uid: String, val generation: String, val enabled: Boolean, val lease: String,
    val bootstrapped: Boolean = false, val bootstrapAfter: String = "", val bootstrapHead: Long = 0,
    val cursor: Long = 0, val status: String = "pending", val lastSyncedAt: Long = 0,
)
@Entity(tableName="sync_remote", primaryKeys=["uid","generation","entityKey"])
data class SyncRemoteEntity(val uid: String, val generation: String, val entityKey: String,
    val version: Long, val payload: String, val deleted: Boolean, val sequence: Long)

@Entity(tableName="sync_public_feeds")
data class SyncPublicFeedEntity(@PrimaryKey val podcastId:String)

/** The revision actually shown locally, distinct from a newer remote conflict candidate. */
@Entity(tableName="sync_views")
data class SyncViewEntity(@PrimaryKey val entityKey:String,val version:Long=0,val sessionId:String="",
    val sequence:Long=0,val checkpointAt:Long=0,val active:Boolean=false,val completionBase:Long=0)

@Entity(tableName="sync_preferences")
data class SyncPreferenceEntity(@PrimaryKey val name:String,val value:String)

@Entity(tableName="sync_artifacts",primaryKeys=["uid","generation","hash"])
data class SyncArtifactEntity(val uid:String,val generation:String,val hash:String,val expandedBytes:Long,val state:String,val localFile:String,val episodeId:String,val revision:String)
