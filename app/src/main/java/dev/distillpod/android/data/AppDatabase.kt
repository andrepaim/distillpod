package dev.distillpod.android.data

import dev.distillpod.android.sync.SyncArtifactEntity
import dev.distillpod.android.sync.SyncPreferenceEntity
import dev.distillpod.android.sync.SyncViewEntity
import dev.distillpod.android.sync.SyncPublicFeedEntity
import dev.distillpod.android.sync.SyncBindingEntity
import dev.distillpod.android.sync.SyncRemoteEntity
import dev.distillpod.android.sync.SyncDao
import dev.distillpod.android.sync.SyncOutboxEntity
import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "podcasts", indices = [Index(value = ["feedUrl"], unique = true)])
data class PodcastEntity(
    @PrimaryKey val id: String, val feedUrl: String, val title: String,
    val author: String = "", val description: String = "", val imageUrl: String? = null,
    val subscribed: Boolean = true, val etag: String? = null, val lastModified: String? = null,
    val refreshedAt: Long = 0, val error: String? = null,
    val originalFeedUrl: String = "",
    val websiteUrl: String? = null,
)

@Entity(tableName = "episodes", foreignKeys = [ForeignKey(entity = PodcastEntity::class, parentColumns = ["id"], childColumns = ["podcastId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("podcastId"), Index(value = ["podcastId", "guid"], unique = true), Index("publishedAt")])
data class EpisodeEntity(
    @PrimaryKey val id: String, val podcastId: String, val guid: String, val title: String, val audioUrl: String,
    val description: String = "", val imageUrl: String? = null, val publishedAt: Long = 0, val durationMs: Long = 0,
    val mimeType: String? = null, val transcriptUrl: String? = null, val transcriptType: String? = null,
    val positionMs: Long = 0, val completed: Boolean = false, val lastPlayedAt: Long = 0,
    val localFile: String? = null, val downloadState: String = "none", val downloadedBytes: Long = 0,
    val totalBytes: Long = 0, val etag: String? = null, val lastModified: String? = null,
    val mediaFingerprint: String = "", val error: String? = null,
    val webpageUrl: String? = null,
)

@Entity(tableName = "queue", foreignKeys = [ForeignKey(entity = EpisodeEntity::class, parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.CASCADE)], indices = [Index("sortOrder")])
data class QueueEntryEntity(@PrimaryKey val episodeId: String, val sortOrder: Int)

@Entity(tableName = "player_session")
data class PlayerSessionEntity(@PrimaryKey val id: Int = 0, val episodeId: String?, val speed: Float = 1f)

@Entity(tableName = "transcripts", foreignKeys = [ForeignKey(entity = EpisodeEntity::class, parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.CASCADE)])
data class TranscriptEntity(
    @PrimaryKey val episodeId: String, val revision: String, val fingerprint: String, val model: String,
    val language: String, val source: String = "whisper", val state: String = "queued",
    val durationMs: Long = 0, val processedMs: Long = 0, val error: String? = null,
    @ColumnInfo(defaultValue = "0") val lookupOnline: Boolean = false,
    @ColumnInfo(defaultValue = "0") val replaceOnline: Boolean = false,
)

@Entity(tableName = "segments", primaryKeys = ["episodeId", "revision", "startMs", "ordinal"],
    foreignKeys = [ForeignKey(entity = EpisodeEntity::class, parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["episodeId", "revision", "endMs"])])
data class SegmentEntity(val episodeId: String, val revision: String, val startMs: Long, val endMs: Long, val text: String, val ordinal: Int = 0)

@Entity(tableName = "transcript_coverage", primaryKeys = ["episodeId", "revision", "startMs"],
    foreignKeys = [ForeignKey(entity = EpisodeEntity::class, parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.CASCADE)])
data class CoverageEntity(val episodeId: String, val revision: String, val startMs: Long, val endMs: Long)

@Entity(tableName = "distillations", foreignKeys = [ForeignKey(entity = EpisodeEntity::class, parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("episodeId"), Index("createdAt"), Index("state")])
data class DistillationEntity(
    @PrimaryKey val id: String, val episodeId: String, val startMs: Long, val endMs: Long,
    val excerpt: String = "", val quote: String? = null, val insight: String? = null,
    val revision: String = "", val fingerprint: String = "", val state: String = "waiting_transcript",
    val createdAt: Long = System.currentTimeMillis(), val error: String? = null, val model: String? = null,
    @ColumnInfo(defaultValue = "''") val attempt: String = "",
)

@Entity(tableName = "models")
data class ModelEntity(@PrimaryKey val id: String, val state: String = "missing", val bytes: Long = 0, val etag: String? = null, val error: String? = null)

@Entity(tableName = "analyses", foreignKeys = [ForeignKey(entity = EpisodeEntity::class, parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.CASCADE)])
data class AnalysisEntity(@PrimaryKey val episodeId: String, val revision: String, val state: String = "queued", val summary: String = "", val chaptersJson: String = "[]", val adsJson: String = "[]", val completedParts: Int = 0, val totalParts: Int = 0, val error: String? = null,
    val model: String? = null, @ColumnInfo(defaultValue = "1") val promptVersion: Int = 1, @ColumnInfo(defaultValue = "''") val attempt: String = "")

@Entity(tableName = "analysis_parts", primaryKeys = ["episodeId", "revision", "part"],
    foreignKeys = [ForeignKey(entity = EpisodeEntity::class, parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.CASCADE)])
data class AnalysisPartEntity(val episodeId: String, val revision: String, val part: Int, val json: String)

@Entity(tableName = "messages", foreignKeys = [ForeignKey(entity = EpisodeEntity::class, parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["episodeId", "createdAt"])])
data class MessageEntity(@PrimaryKey val id: String, val episodeId: String, val role: String, val text: String, val state: String = "succeeded", val createdAt: Long = System.currentTimeMillis(), val replyTo: String? = null, val error: String? = null,
    @ColumnInfo(defaultValue = "'[]'") val sourcesJson: String = "[]", @ColumnInfo(defaultValue = "''") val revision: String = "",
    @ColumnInfo(defaultValue = "''") val fingerprint: String = "", val model: String? = null, @ColumnInfo(defaultValue = "''") val attempt: String = "")

@Entity(tableName = "research", foreignKeys = [ForeignKey(entity = DistillationEntity::class, parentColumns = ["id"], childColumns = ["distillationId"], onDelete = ForeignKey.CASCADE)], indices = [Index("distillationId")])
data class ResearchEntity(@PrimaryKey val id: String, val distillationId: String, val state: String = "queued", val report: String = "", val sourcesJson: String = "[]", val mode: String = "transcript", val createdAt: Long = System.currentTimeMillis(), val error: String? = null)

@Entity(tableName = "suggestions")
data class SuggestionEntity(@PrimaryKey val feedUrl: String, val title: String, val author: String = "", val imageUrl: String? = null, val reason: String, val dismissed: Boolean = false, val createdAt: Long = System.currentTimeMillis())

@Dao
interface LibraryDao {
    @Query("SELECT * FROM online_transcripts") fun observeOnlineTranscripts(): Flow<List<OnlineTranscriptEntity>>
    @Query("SELECT * FROM online_transcripts WHERE episodeId=:id") suspend fun onlineTranscript(id: String): OnlineTranscriptEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putOnlineTranscript(value: OnlineTranscriptEntity)
    @Query("UPDATE online_transcripts SET selected=0 WHERE episodeId=:id") suspend fun deselectOnline(id: String)
    @Query("SELECT * FROM transcript_lookup WHERE episodeId=:id") suspend fun transcriptLookup(id: String): TranscriptLookupEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTranscriptLookup(value: TranscriptLookupEntity)

    @Query("SELECT * FROM post_drafts ORDER BY updatedAt DESC") fun observePosts(): Flow<List<PostDraftEntity>>
    @Query("SELECT * FROM post_drafts WHERE id=:id") suspend fun post(id: String): PostDraftEntity?
    @Upsert suspend fun putPost(value: PostDraftEntity)
    @Query("DELETE FROM post_drafts WHERE id=:id") suspend fun deletePost(id: String)

    @Query("SELECT * FROM podcasts ORDER BY title COLLATE NOCASE") fun observePodcasts(): Flow<List<PodcastEntity>>
    @Query("SELECT * FROM podcasts ORDER BY title COLLATE NOCASE") suspend fun podcasts(): List<PodcastEntity>
    @Query("SELECT * FROM podcasts WHERE id=:id") suspend fun podcast(id: String): PodcastEntity?
    @Query("SELECT * FROM podcasts WHERE feedUrl=:url OR originalFeedUrl=:url LIMIT 1") suspend fun podcastByUrl(url: String): PodcastEntity?
    @Upsert suspend fun putPodcast(value: PodcastEntity)
    @Query("DELETE FROM podcasts WHERE id=:id") suspend fun deletePodcast(id: String)
    @Query("SELECT * FROM episodes ORDER BY publishedAt DESC, title") fun observeEpisodes(): Flow<List<EpisodeEntity>>
    @Query("SELECT * FROM episodes ORDER BY publishedAt DESC, title") suspend fun episodes(): List<EpisodeEntity>
    @Query("SELECT * FROM episodes WHERE podcastId=:id ORDER BY publishedAt DESC") suspend fun episodesForPodcast(id: String): List<EpisodeEntity>
    @Query("SELECT * FROM episodes WHERE id=:id") suspend fun episode(id: String): EpisodeEntity?
    @Upsert suspend fun putEpisode(value: EpisodeEntity)
    @Upsert suspend fun putEpisodes(values: List<EpisodeEntity>)
    @Query("UPDATE episodes SET positionMs=:position, durationMs=CASE WHEN :duration>0 THEN :duration ELSE durationMs END, completed=CASE WHEN :complete THEN 1 ELSE completed END, lastPlayedAt=:at WHERE id=:id")
    suspend fun progress(id: String, position: Long, duration: Long, complete: Boolean, at: Long)
    @Query("UPDATE episodes SET completed=:complete WHERE id=:id") suspend fun markCompleted(id: String, complete: Boolean)
    @Query("UPDATE episodes SET downloadState=:state, downloadedBytes=:bytes, totalBytes=:total, etag=:etag, lastModified=:modified, error=:error WHERE id=:id")
    suspend fun downloadProgress(id: String, state: String, bytes: Long, total: Long, etag: String?, modified: String?, error: String?)
    @Query("UPDATE episodes SET localFile=:file, mediaFingerprint=:fingerprint, downloadState='downloaded', downloadedBytes=:bytes, totalBytes=:bytes, error=NULL WHERE id=:id")
    suspend fun downloadComplete(id: String, file: String, fingerprint: String, bytes: Long)
    @Query("UPDATE episodes SET localFile=NULL, downloadState='none', downloadedBytes=0, error=NULL WHERE id=:id") suspend fun removeDownload(id: String)
    @Query("SELECT * FROM queue ORDER BY sortOrder") suspend fun queue(): List<QueueEntryEntity>
    @Query("SELECT * FROM queue ORDER BY sortOrder") fun observeQueue(): Flow<List<QueueEntryEntity>>
    @Query("DELETE FROM queue") suspend fun clearQueue()
    @Insert suspend fun insertQueue(entries: List<QueueEntryEntity>)
    @Query("SELECT * FROM player_session WHERE id=0") suspend fun playerSession(): PlayerSessionEntity?
    @Upsert suspend fun putPlayerSession(value: PlayerSessionEntity)
    @Query("SELECT * FROM transcripts") fun observeTranscripts(): Flow<List<TranscriptEntity>>
    @Query("SELECT * FROM transcripts") suspend fun transcripts(): List<TranscriptEntity>
    @Query("UPDATE episodes SET durationMs=:duration WHERE id=:id") suspend fun duration(id: String, duration: Long)
    @Query("SELECT * FROM transcripts WHERE episodeId=:id") suspend fun transcript(id: String): TranscriptEntity?
    @Upsert suspend fun putTranscript(value: TranscriptEntity)
    @Query("SELECT * FROM segments WHERE episodeId=:id AND revision=:revision ORDER BY startMs,ordinal") suspend fun segments(id: String, revision: String): List<SegmentEntity>
    @Query("SELECT * FROM segments WHERE episodeId=:id AND revision=:revision AND endMs>:start AND startMs<:end ORDER BY startMs,ordinal") suspend fun window(id: String, revision: String, start: Long, end: Long): List<SegmentEntity>
    @Upsert suspend fun putSegments(values: List<SegmentEntity>)
    @Query("SELECT * FROM transcript_coverage WHERE episodeId=:id AND revision=:revision ORDER BY startMs") suspend fun coverage(id: String, revision: String): List<CoverageEntity>
    @Upsert suspend fun putCoverage(value: CoverageEntity)
    @Query("DELETE FROM segments WHERE episodeId=:id") suspend fun deleteSegments(id: String)
    @Query("DELETE FROM transcript_coverage WHERE episodeId=:id") suspend fun deleteCoverage(id: String)
    @Query("SELECT * FROM distillations ORDER BY createdAt DESC") fun observeDistillations(): Flow<List<DistillationEntity>>
    @Query("SELECT * FROM distillations ORDER BY createdAt DESC") suspend fun distillations(): List<DistillationEntity>
    @Query("SELECT * FROM distillations WHERE id=:id") suspend fun distillation(id: String): DistillationEntity?
    @Query("SELECT * FROM distillations WHERE episodeId=:id AND createdAt>:after AND ABS(endMs-:position)<1000 ORDER BY createdAt DESC LIMIT 1") suspend fun recentCapture(id: String, position: Long, after: Long): DistillationEntity?
    @Upsert suspend fun putDistillation(value: DistillationEntity)
    @Query("DELETE FROM distillations WHERE id=:id") suspend fun deleteDistillation(id: String)
    @Query("SELECT * FROM models") fun observeModels(): Flow<List<ModelEntity>>
    @Query("SELECT * FROM models WHERE id=:id") suspend fun model(id: String): ModelEntity?
    @Upsert suspend fun putModel(value: ModelEntity)
    @Query("SELECT * FROM analyses") fun observeAnalyses(): Flow<List<AnalysisEntity>>
    @Query("SELECT * FROM analyses") suspend fun analyses(): List<AnalysisEntity>
    @Query("SELECT * FROM analyses WHERE episodeId=:id") suspend fun analysis(id: String): AnalysisEntity?
    @Upsert suspend fun putAnalysis(value: AnalysisEntity)
    @Query("SELECT * FROM analysis_parts WHERE episodeId=:id AND revision=:revision ORDER BY part") suspend fun analysisParts(id: String, revision: String): List<AnalysisPartEntity>
    @Upsert suspend fun putAnalysisPart(value: AnalysisPartEntity)
    @Query("DELETE FROM analysis_parts WHERE episodeId=:id") suspend fun deleteAnalysisParts(id: String)
    @Query("SELECT * FROM messages ORDER BY createdAt,id") fun observeMessages(): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages ORDER BY createdAt,id") suspend fun messages(): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE id=:id") suspend fun message(id: String): MessageEntity?
    @Upsert suspend fun putMessage(value: MessageEntity)
    @Query("SELECT * FROM research ORDER BY createdAt DESC") fun observeResearch(): Flow<List<ResearchEntity>>
    @Query("SELECT * FROM research ORDER BY createdAt DESC") suspend fun researches(): List<ResearchEntity>
    @Query("SELECT * FROM research WHERE id=:id") suspend fun research(id: String): ResearchEntity?
    @Upsert suspend fun putResearch(value: ResearchEntity)
    @Query("SELECT * FROM suggestions ORDER BY createdAt DESC") fun observeSuggestions(): Flow<List<SuggestionEntity>>
    @Query("SELECT * FROM suggestions ORDER BY createdAt DESC") suspend fun suggestions(): List<SuggestionEntity>
    @Upsert suspend fun putSuggestions(values: List<SuggestionEntity>)
    @Query("UPDATE suggestions SET dismissed=1 WHERE feedUrl=:url") suspend fun dismissSuggestion(url: String)
}

@Database(entities = [OnlineTranscriptEntity::class, TranscriptLookupEntity::class, PostDraftEntity::class, PodcastEntity::class, EpisodeEntity::class, QueueEntryEntity::class, PlayerSessionEntity::class,
    TranscriptEntity::class, SegmentEntity::class, CoverageEntity::class, DistillationEntity::class, ModelEntity::class,
    AnalysisEntity::class, AnalysisPartEntity::class, MessageEntity::class, ResearchEntity::class, SuggestionEntity::class, SyncOutboxEntity::class, SyncBindingEntity::class, SyncRemoteEntity::class, SyncPublicFeedEntity::class, SyncViewEntity::class, SyncPreferenceEntity::class, SyncArtifactEntity::class], version = 8, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun sync(): SyncDao
    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE analyses ADD COLUMN model TEXT")
                db.execSQL("ALTER TABLE analyses ADD COLUMN promptVersion INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE analyses ADD COLUMN attempt TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE distillations ADD COLUMN attempt TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN sourcesJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE messages ADD COLUMN revision TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN fingerprint TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN model TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN attempt TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_outbox (operationId TEXT NOT NULL, uid TEXT NOT NULL, generation TEXT NOT NULL, entityKey TEXT NOT NULL, baseVersion INTEGER NOT NULL, payload TEXT NOT NULL, deleted INTEGER NOT NULL, PRIMARY KEY(operationId))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_uid_generation ON sync_outbox (uid,generation)")
            }
        }
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_outbox ADD COLUMN state TEXT NOT NULL DEFAULT 'queued'")
                db.execSQL("ALTER TABLE sync_outbox ADD COLUMN baseline INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_binding (id INTEGER NOT NULL, uid TEXT NOT NULL, generation TEXT NOT NULL, enabled INTEGER NOT NULL, lease TEXT NOT NULL, bootstrapped INTEGER NOT NULL, bootstrapAfter TEXT NOT NULL, bootstrapHead INTEGER NOT NULL, cursor INTEGER NOT NULL, status TEXT NOT NULL, lastSyncedAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_remote (uid TEXT NOT NULL, generation TEXT NOT NULL, entityKey TEXT NOT NULL, version INTEGER NOT NULL, payload TEXT NOT NULL, deleted INTEGER NOT NULL, sequence INTEGER NOT NULL, PRIMARY KEY(uid,generation,entityKey))")
            }
        }
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_public_feeds (podcastId TEXT NOT NULL, PRIMARY KEY(podcastId))")
            }
        }
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_views (entityKey TEXT NOT NULL, version INTEGER NOT NULL, sessionId TEXT NOT NULL, sequence INTEGER NOT NULL, checkpointAt INTEGER NOT NULL, active INTEGER NOT NULL, completionBase INTEGER NOT NULL, PRIMARY KEY(entityKey))")
                db.execSQL("INSERT INTO sync_views (entityKey,version,sessionId,sequence,checkpointAt,active,completionBase) SELECT entityKey,version,'',0,0,0,0 FROM sync_remote WHERE uid=(SELECT uid FROM sync_binding WHERE id=0) AND generation=(SELECT generation FROM sync_binding WHERE id=0)")
            }
        }
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_artifacts (uid TEXT NOT NULL, generation TEXT NOT NULL, hash TEXT NOT NULL, expandedBytes INTEGER NOT NULL, state TEXT NOT NULL, localFile TEXT NOT NULL, episodeId TEXT NOT NULL, revision TEXT NOT NULL, PRIMARY KEY(uid,generation,hash))")
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_preferences (name TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(name))")
            }
        }
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcasts ADD COLUMN websiteUrl TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN webpageUrl TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS post_drafts (id TEXT NOT NULL, sourceMomentId TEXT, content TEXT NOT NULL, revision INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(sourceMomentId) REFERENCES distillations(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_post_drafts_sourceMomentId ON post_drafts(sourceMomentId)")
                db.execSQL("ALTER TABLE transcripts ADD COLUMN lookupOnline INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transcripts ADD COLUMN replaceOnline INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS online_transcripts (episodeId TEXT NOT NULL, revision TEXT NOT NULL, audioUrl TEXT NOT NULL, providerId TEXT NOT NULL, retrievedAt INTEGER NOT NULL, createdAt TEXT NOT NULL, durationMs INTEGER NOT NULL, language TEXT NOT NULL, segmentsJson TEXT NOT NULL, contentHash TEXT NOT NULL, selected INTEGER NOT NULL, PRIMARY KEY(episodeId), FOREIGN KEY(episodeId) REFERENCES episodes(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE IF NOT EXISTS transcript_lookup (episodeId TEXT NOT NULL, identity TEXT NOT NULL, retryAt INTEGER NOT NULL, PRIMARY KEY(episodeId), FOREIGN KEY(episodeId) REFERENCES episodes(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
        fun open(context: Context): AppDatabase = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "library.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build()
    }
}
