package dev.distillpod.android.data

import androidx.room.*
import androidx.work.*
import dev.distillpod.android.AppGraph
import dev.distillpod.android.appGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import java.io.File

@Entity(tableName = "show_automation")
data class ShowAutomationEntity(@PrimaryKey val podcastId: String, val enabledAt: Long, val initialized: Boolean = false)
@Entity(tableName = "episode_automation")
data class EpisodeAutomationEntity(@PrimaryKey val episodeId: String, val podcastId: String, val stage: String = "queued")
@Entity(tableName = "episode_ad_preferences")
data class EpisodeAdPreferenceEntity(@PrimaryKey val episodeId: String, val revision: String, val enabled: Boolean)

@Dao
interface AutomationDao {
    @Query("SELECT * FROM show_automation") fun observeShows(): Flow<List<ShowAutomationEntity>>
    @Query("SELECT * FROM episode_automation") fun observeJobs(): Flow<List<EpisodeAutomationEntity>>
    @Query("SELECT * FROM episode_ad_preferences") fun observeAds(): Flow<List<EpisodeAdPreferenceEntity>>
    @Query("SELECT * FROM show_automation WHERE podcastId=:id") suspend fun show(id: String): ShowAutomationEntity?
    @Query("SELECT * FROM episode_ad_preferences WHERE episodeId=:id") suspend fun ad(id: String): EpisodeAdPreferenceEntity?
    @Query("SELECT * FROM episode_automation") suspend fun jobs(): List<EpisodeAutomationEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putShow(row: ShowAutomationEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertJob(row: EpisodeAutomationEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putJob(row: EpisodeAutomationEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAd(row: EpisodeAdPreferenceEntity)
    @Query("UPDATE show_automation SET initialized=1 WHERE podcastId=:id AND initialized=0") suspend fun initialize(id: String)
    @Query("DELETE FROM show_automation WHERE podcastId=:id") suspend fun disable(id: String)
    @Query("DELETE FROM episode_automation WHERE podcastId=:id") suspend fun deleteJobs(id: String)
}

internal fun canSkipMarkedAds(ep: EpisodeEntity, transcript: TranscriptEntity?, analysis: AnalysisEntity?) =
    ep.localFile != null && transcript?.state == "ready" && transcript.source != "online" &&
        transcript.fingerprint == ep.mediaFingerprint && analysis?.state == "succeeded" && analysis.revision == transcript.revision

internal fun adSelectionRevision(analysis: AnalysisEntity) = "${analysis.revision}:${analysis.attempt}"

internal suspend fun setEpisodeAdSkipping(db: AppDatabase, id: String, enabled: Boolean) = db.withTransaction {
    val ep = db.library().episode(id) ?: return@withTransaction
    val transcript = db.library().transcript(id)
    val analysis = db.library().analysis(id)
    require(canSkipMarkedAds(ep, transcript, analysis)) { "Prepare the current episode first" }
    val current = requireNotNull(analysis)
    // The first opt-in selects suggested ranges. Preserve subsequent review exclusions.
    if (enabled && db.automation().ad(id)?.revision != adSelectionRevision(current)) {
        val ads = org.json.JSONArray(current.adsJson)
        repeat(ads.length()) { ads.getJSONObject(it).put("approved", true) }
        db.library().putAnalysis(current.copy(adsJson = ads.toString()))
    }
    db.automation().putAd(EpisodeAdPreferenceEntity(id, adSelectionRevision(current), enabled))
}

/** Device-local durable intent. Existing workers own downloads, inference and analysis checkpoints. */
class ShowAutomation(private val graph: AppGraph) {
    private val dao = graph.db.automation()
    private val library = graph.db.library()
    private val controls = kotlinx.coroutines.sync.Mutex()
    suspend fun setEnabled(id: String, enabled: Boolean) = controls.withLock {
        graph.db.withTransaction {
            if (enabled) {
                if (dao.show(id) == null) dao.putShow(ShowAutomationEntity(id, System.currentTimeMillis()))
            } else {
                // Shared manual workers may finish; removing intent stops subsequent stages.
                dao.disable(id)
                dao.deleteJobs(id)
            }
        }
        if (enabled) graph.jobs.refresh(id)
    }

    suspend fun retry(id: String) {
        dao.jobs().firstOrNull { it.episodeId == id }?.let { dao.putJob(it.copy(stage = "retry")) }
    }
    fun kick() {
        WorkManager.getInstance(graph.context).enqueueUniqueWork("show-automation", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ShowAutomationWorker>().build())
    }
    @OptIn(FlowPreview::class)
    fun observe() {
        graph.scope.launch(Dispatchers.IO) {
            // Persist a continuation whenever a dependency changes. Debounce byte/chunk progress.
            merge(library.observeEpisodes().map { Unit }, library.observeTranscripts().map { Unit },
                library.observeAnalyses().map { Unit }, dao.observeShows().map { Unit }, dao.observeJobs().map { Unit },
                graph.settings.flow.map { Unit }, graph.account.state.map { Unit })
                .debounce(500).collect {
                    if (dao.jobs().any { it.stage != "ready" }) kick()
                }
        }
    }
    suspend fun advance() = controls.withLock {
        AutomationProcessor(graph.db, { graph.account.state.value.connected }, graph.jobs::download,
            { graph.transcription.prepare(it, allowOnline = false) }, graph.understanding::analyze).advance()
    }
}

internal class AutomationProcessor(private val db: AppDatabase, private val connected: () -> Boolean,
    private val download: suspend (String) -> Unit, private val transcribe: suspend (String) -> Unit,
    private val analyze: suspend (String) -> Unit) {
    private val dao = db.automation()
    private val library = db.library()
    suspend fun advance() {
        for (job in dao.jobs().filter { it.stage != "ready" }) {
            val show = dao.show(job.podcastId) ?: continue
            if (!show.initialized || library.podcast(job.podcastId)?.subscribed != true) continue
            val ep = library.episode(job.episodeId) ?: continue
            suspend fun stage(value: String) { if (job.stage != value) dao.putJob(job.copy(stage = value)) }
            if (ep.localFile?.let { File(it).isFile } != true) {
                if (ep.downloadState in listOf("paused", "failed") && job.stage != "retry") continue
                if (job.stage in listOf("queued", "retry") || ep.downloadState == "none") {
                    stage("downloading"); download(ep.id)
                }
                // A pause/failure is retried explicitly from the show page.
                if (ep.downloadState !in listOf("paused", "failed")) return
                continue
            }
            val transcript = library.transcript(ep.id)
            if (transcript?.state != "ready" || transcript.fingerprint != ep.mediaFingerprint || transcript.source == "online") {
                if (transcript?.state in listOf("paused", "failed", "partial", "stale") && job.stage != "retry") continue
                if (job.stage in listOf("queued", "retry") || transcript == null) {
                    stage("transcribing"); transcribe(ep.id)
                }
                if (transcript?.state !in listOf("paused", "failed", "waiting_model", "partial", "stale")) return
                continue
            }
            val analysis = library.analysis(ep.id)
            if (analysis?.state == "succeeded" && analysis.revision == transcript.revision) { stage("ready"); continue }
            if (!connected()) { stage("waiting_auth"); continue }
            if (analysis == null || analysis.revision != transcript.revision || job.stage == "retry") {
                stage("analyzing"); analyze(ep.id)
            }
            if (analysis?.state !in listOf("failed", "paused", "waiting_auth", "waiting_quota")) return
        }
    }
}

class ShowAutomationWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.appGraph
        graph.ready.await()
        return try { graph.automation.advance(); Result.success() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { graph.diagnostics.failure("worker", error); if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }
}
