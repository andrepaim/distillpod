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
    /** Keeps discovery order and cannot recreate a job removed by disabling automation meanwhile. */
    @Query("UPDATE episode_automation SET stage=:stage WHERE episodeId=:id") suspend fun setStage(id: String, stage: String)
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
        dao.setStage(id, "retry")
    }
    private fun kick() {
        WorkManager.getInstance(graph.context).enqueueUniqueWork("show-automation", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ShowAutomationWorker>().build())
    }
    /** Workers change the observed rows; their state changes, not their progress, wake the coordinator. */
    fun observe() {
        graph.scope.launch(Dispatchers.IO) {
            automationKickRequests(dao.observeJobs(), dao.observeShows(), library.observePodcasts(), library.observeEpisodes(),
                library.observeTranscripts(), library.observeAnalyses(), graph.account.state.map { it.connected }).collect { kick() }
        }
    }
    suspend fun advance() = controls.withLock {
        AutomationProcessor(graph.db, { graph.account.state.value.connected }, graph.jobs::download,
            { graph.transcription.prepare(it, allowOnline = false) }, graph.understanding::analyze).advance()
    }
}

private val STARTING_STAGES = listOf("queued", "retry")
private val ACTIVE_DOWNLOAD = listOf("queued", "downloading")
private val ACTIVE_TRANSCRIPT = listOf("queued", "processing")
private val ACTIVE_ANALYSIS = listOf("queued", "running")
/** Stopped by the user or a failure; automation restarts these only after Retry preparation. */
private val HELD_DOWNLOAD = listOf("paused", "failed")
private val HELD_TRANSCRIPT = listOf("paused", "failed", "partial", "stale")

/**
 * Advances jobs in discovery order. Only actively running work (a queued or running download,
 * transcript or analysis) holds later jobs back, so processing stays one at a time. A job that
 * cannot progress without the user is staged `blocked` (or `waiting_auth`) and is skipped; it
 * resumes when its download, transcript, analysis or account state changes or after Retry preparation.
 */
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
            if (step(job, ep)) return
        }
    }

    /** Returns true while this job has running work that later jobs wait for. */
    private suspend fun step(job: EpisodeAutomationEntity, ep: EpisodeEntity): Boolean {
        var current = job.stage
        suspend fun stage(value: String) { if (current != value) { dao.setStage(job.episodeId, value); current = value } }
        val retry = job.stage == "retry"
        val starting = job.stage in STARTING_STAGES
        if (!ep.hasAudio()) {
            val start = when (ep.downloadState) {
                in ACTIVE_DOWNLOAD -> starting // Make sure a new or retried job's request exists.
                in HELD_DOWNLOAD -> retry
                else -> true // Not downloaded, or its file is gone.
            }
            if (start) { stage("downloading"); download(ep.id) }
            val now = library.episode(ep.id) ?: return false
            if (now.downloadState in ACTIVE_DOWNLOAD || now.hasAudio()) { stage("downloading"); return true }
            stage("blocked"); return false
        }
        fun usable(t: TranscriptEntity?) = t?.state == "ready" && t.fingerprint == ep.mediaFingerprint && t.source != "online"
        var transcript = library.transcript(ep.id)
        if (!usable(transcript)) {
            val start = transcript == null || retry || (starting && transcript.state !in HELD_TRANSCRIPT)
            if (start) { stage("transcribing"); transcribe(ep.id); transcript = library.transcript(ep.id) }
            if (!usable(transcript)) {
                if (transcript?.state in ACTIVE_TRANSCRIPT) { stage("transcribing"); return true }
                // Held, waiting for a model, or unusable text: Retry preparation transcribes locally.
                stage("blocked"); return false
            }
        }
        val revision = transcript!!.revision
        fun done(a: AnalysisEntity?) = a?.state == "succeeded" && a.revision == revision
        var analysis = library.analysis(ep.id)
        if (done(analysis)) { stage("ready"); return false }
        if (!connected()) { stage("waiting_auth"); return false }
        if (analysis == null || analysis.revision != revision || retry) { stage("analyzing"); analyze(ep.id); analysis = library.analysis(ep.id) }
        if (done(analysis)) { stage("ready"); return false }
        if (analysis?.state in ACTIVE_ANALYSIS) { stage("analyzing"); return true }
        stage("blocked"); return false
    }

    private fun EpisodeEntity.hasAudio() = localFile?.let { File(it).isFile } == true
}

/** What [AutomationProcessor] decides on for one unfinished job; positions, bytes and chunk progress are left out. */
internal data class AutomationJobKey(val episodeId: String, val stage: String, val show: String, val subscribed: Boolean,
    val download: String?, val localFile: String?, val media: String?, val transcript: String?, val analysis: String?)
internal data class AutomationFingerprint(val connected: Boolean, val jobs: List<AutomationJobKey>)

private fun phase(state: String, active: List<String>) = if (state in active) "active" else state

internal fun automationFingerprint(jobs: List<EpisodeAutomationEntity>, shows: List<ShowAutomationEntity>, podcasts: List<PodcastEntity>,
    episodes: List<EpisodeEntity>, transcripts: List<TranscriptEntity>, analyses: List<AnalysisEntity>, connected: Boolean): AutomationFingerprint {
    val pending = jobs.filter { it.stage != "ready" }
    val ids = pending.mapTo(HashSet()) { it.episodeId }
    val episode = episodes.filter { it.id in ids }.associateBy { it.id }
    val transcript = transcripts.filter { it.episodeId in ids }.associateBy { it.episodeId }
    val analysis = analyses.filter { it.episodeId in ids }.associateBy { it.episodeId }
    val show = shows.associateBy { it.podcastId }
    val subscribed = podcasts.filter { it.subscribed }.mapTo(HashSet()) { it.id }
    return AutomationFingerprint(connected, pending.map { job ->
        val ep = episode[job.episodeId]
        val t = transcript[job.episodeId]
        val a = analysis[job.episodeId]
        AutomationJobKey(job.episodeId, if (job.stage in STARTING_STAGES) job.stage else "started",
            show[job.podcastId]?.let { if (it.initialized) "initialized" else "baseline" } ?: "disabled", job.podcastId in subscribed,
            ep?.let { phase(it.downloadState, ACTIVE_DOWNLOAD) }, ep?.localFile, ep?.mediaFingerprint,
            t?.let { "${phase(it.state, ACTIVE_TRANSCRIPT)}:${it.revision}:${it.fingerprint}:${it.source}" },
            a?.let { "${phase(it.state, ACTIVE_ANALYSIS)}:${it.revision}" })
    })
}

/**
 * Requests a coordinator run when [automationFingerprint] changes and unfinished jobs exist.
 * Playback writes the episodes table every second; those writes no longer schedule work (#140).
 */
@OptIn(FlowPreview::class)
internal fun automationKickRequests(jobs: Flow<List<EpisodeAutomationEntity>>, shows: Flow<List<ShowAutomationEntity>>,
    podcasts: Flow<List<PodcastEntity>>, episodes: Flow<List<EpisodeEntity>>, transcripts: Flow<List<TranscriptEntity>>,
    analyses: Flow<List<AnalysisEntity>>, connected: Flow<Boolean>): Flow<AutomationFingerprint> {
    val media = combine(episodes, transcripts, analyses) { e, t, a -> Triple(e, t, a) }
    return combine(jobs, shows, podcasts, media, connected) { j, s, p, (e, t, a), c -> automationFingerprint(j, s, p, e, t, a, c) }
        .debounce(500).distinctUntilChanged().filter { it.jobs.isNotEmpty() }
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
