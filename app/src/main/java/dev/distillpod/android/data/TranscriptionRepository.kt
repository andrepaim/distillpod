package dev.distillpod.android.data

import androidx.room.withTransaction
import androidx.work.*
import dev.distillpod.android.AppGraph
import dev.distillpod.android.AudioClipDecoder
import dev.distillpod.android.NativeWhisper
import dev.distillpod.android.appGraph
import dev.distillpod.android.work.workForeground
import dev.distillpod.android.work.TranscriptionService
import dev.distillpod.android.work.withNetworkType
import dev.distillpod.core.*
import dev.distillpod.android.diagnostics.DiagnosticCorrelation
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

private const val CHUNK_MS = 120_000L
/** Refused foreground starts `LibraryWorker` retries before pausing a download (#138). Transcription no longer retries (#301). */
internal const val MAX_FOREGROUND_START_ATTEMPTS = 5
/** Earlier versions paused a transcript with this after five refused background starts (#56); startup resumes it (#301). */
internal const val LEGACY_TRANSCRIPTION_BLOCKED_MESSAGE = "Android could not start transcription. Tap Resume to continue from saved chunks."
/** Settled transcript states whose text a local replacement keeps readable (#229); never stale audio or running work. */
private val KEPT_STATES = listOf("ready", "partial", "failed", "paused", "waiting_model")
enum class TranscriptStage { ONLINE, PUBLISHER, DOWNLOADING, CHECKING_MODEL, DECODING, STARTING, TRANSCRIBING, SAVING, QUEUED }
data class TranscriptProgress(val revision: String, val run: String, val stage: TranscriptStage,
    val workedMs: Long, val durationMs: Long, val chunkStartMs: Long = 0, val chunkEndMs: Long = 0)

internal class TranscriptExecution(val stopped: () -> Boolean, val foreground: suspend () -> Unit, val reschedule: Boolean)

enum class TranscriptOutcome { COMPLETED, FAILED }

/**
 * The wait each queued revision last logged (#294). Every rescheduling pass enqueues all waiting
 * revisions again, and a foreground session's stop recovers them once more, so `transcription.queued`
 * is logged when a revision enters the queue or its reason changes. Any other lifecycle event ends it.
 */
private class QueuedWaits {
    private val logged = ConcurrentHashMap<String, String>()
    fun enters(revision: String, reason: String): Boolean = logged.put(revision, reason) != reason
    fun leaves(revision: String) { logged.remove(revision) }
}

/** A queued revision waits its turn behind another transcript (#299): transcripts are prepared one at a time. */
internal const val QUEUED_BEHIND = "queued_behind"

/**
 * The order one transcription session takes queued revisions in (#299): revisions the user asked for
 * ([requested]: revision to request sequence) first, in the order asked, then automatic ones oldest first.
 */
internal fun transcriptQueueOrder(rows: List<TranscriptEntity>, requested: Map<String, Long>): List<TranscriptEntity> =
    rows.sortedWith(compareBy<TranscriptEntity>({ requested[it.revision] ?: Long.MAX_VALUE }, { it.localCreatedAt }, { it.episodeId }))
internal fun Exception.isForegroundStartRejected(): Boolean {
    val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this
    while (current != null && visited.add(current)) {
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            current is android.app.ForegroundServiceStartNotAllowedException) return true
        current = current.cause
    }
    return false
}

internal fun TranscriptOutcome.workerResult(): ListenableWorker.Result = when (this) {
    TranscriptOutcome.COMPLETED -> ListenableWorker.Result.success()
    TranscriptOutcome.FAILED -> ListenableWorker.Result.failure()
}

class TranscriptionRepository internal constructor(private val graph: AppGraph,
    private val dao: LibraryDao,
    private val onlineLookup: suspend (EpisodeEntity, TranscriptEntity) -> Boolean) {
    constructor(graph: AppGraph,
        onlineLookup: suspend (EpisodeEntity, TranscriptEntity) -> Boolean = { ep, row -> graph.onlineTranscripts.lookup(ep, row) }) :
        this(graph, graph.db.library(), onlineLookup)
    private val manager get() = WorkManager.getInstance(graph.context)
    private val processing = Mutex()
    private val controls = Mutex()
    private val scheduling = Mutex()
    private val running = ConcurrentHashMap<String, Job>()
    private val live = MutableStateFlow<Map<String, TranscriptProgress>>(emptyMap())
    val progress = live.asStateFlow()
    private fun correlation(revision:String,run:String?=null):DiagnosticCorrelation =
        runCatching {DiagnosticCorrelation(transcriptRevisionId=revision,executionId=run)}.getOrDefault(DiagnosticCorrelation())
    private val waits = QueuedWaits()
    private val shownWaits = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Each queued revision's latest wait reason, as logged to `transcription.queued`, for its status text (#299). */
    val waitReasons = shownWaits.asStateFlow()
    /**
     * Revisions the user asked for, with their request sequence (#299). Kept in memory, not in Room: the
     * session that takes them in order runs in this process. After a restart they run oldest first.
     * Deferred WorkManager chunks (the session refused) run in the order Android schedules them.
     */
    private val requested = ConcurrentHashMap<String, Long>()
    private val requests = java.util.concurrent.atomic.AtomicLong()
    private fun request(revision: String) { requested.putIfAbsent(revision, requests.incrementAndGet()) }
    /** A revision's lifecycle event. An unchanged wait is not logged again; without consent nothing is remembered. */
    private fun lifecycle(name:String,revision:String,reason:String="none",attempt:String?=null,correlation:DiagnosticCorrelation) {
        when (name) {
            "transcription.queued" -> {
                shownWaits.update { it + (revision to reason) }
                if(graph.diagnostics.enabled && !waits.enters(revision,reason))return
            }
            "transcription.started" -> { waits.leaves(revision); shownWaits.update { it - revision } }
            else -> leftQueue(revision)
        }
        graph.diagnostics.event(name,revision,reason=reason,attempt=attempt,correlation=correlation)
    }
    /** The revision stopped waiting for good: paused, finished, failed or replaced. */
    private fun leftQueue(revision: String) { waits.leaves(revision); shownWaits.update { it - revision }; requested.remove(revision) }
    /**
     * [requested]: the user asked for this transcript (Transcribe, or a moment that needs it), so it goes
     * ahead of automatic transcripts. The chunk already in progress finishes first.
     */
    suspend fun prepare(id: String, forceLocal: Boolean = false, allowOnline: Boolean = true, requested: Boolean = false) =
        controls.withLock { prepareInternal(id, forceLocal, allowOnline, requested) }
    /** Playback must not undo a pause, retry a failure or regenerate text after a model change. */
    suspend fun prepareAutomatically(id: String) = controls.withLock {
        if (!graph.settings.flow.first().transcribeOnPlayback) return@withLock
        val ep = dao.episode(id) ?: return@withLock // Diagnostic clips are not library episodes.
        // Download completion updates the audio fingerprint before the active chunk adopts it.
        // A streaming-to-file playback transition must not replace that in-flight revision.
        if (running[id]?.isActive == true) return@withLock
        val old = dao.transcript(id)
        if (old?.state == "paused") return@withLock
        // A transcript waiting for a model goes on once a model it may use is ready (#225).
        if (old?.state == "waiting_model" && old.fingerprint == ep.mediaFingerprint &&
            !modelReady(continuing(old, graph.settings.flow.first()).model)) return@withLock
        if (old != null && old.fingerprint == ep.mediaFingerprint && old.state !in listOf("stale", "waiting_model")) return@withLock
        prepareInternal(id, forceLocal = false, allowOnline = true, requested = false)
    }

    private fun modelFile(id: String) = File(graph.media.modelsDirectory, "$id.bin")
    private suspend fun modelReady(id: String) = dao.model(id)?.state == "ready" && modelFile(id).isFile
    private suspend fun hasSavedChunks(row: TranscriptEntity) = row.processedMs > 0 || dao.coverage(row.episodeId, row.revision).isNotEmpty()

    /**
     * The settings a resumed local revision uses. Saved chunks keep the model and language the revision
     * started with (#228). A revision with nothing saved adopts the current settings, but switches to
     * another model only once that model is ready (#225).
     */
    private suspend fun continuing(row: TranscriptEntity, prefs: Preferences): TranscriptEntity {
        if (row.source != "whisper" || row.model == prefs.model && row.language == prefs.language) return row
        if (hasSavedChunks(row)) return row
        if (row.model != prefs.model && !modelReady(prefs.model)) return row
        return row.copy(model = prefs.model, language = prefs.language)
    }

    private suspend fun prepareInternal(id: String, forceLocal: Boolean, allowOnline: Boolean, requested: Boolean) {
        val ep = dao.episode(id) ?: error("Episode is unavailable")
        val prefs = graph.settings.flow.first()
        val old = dao.transcript(id)
        val replacingOnline = forceLocal || old?.let { it.replaceOnline && it.state !in listOf("ready", "stale") } == true
        val onlineAllowed = allowOnline && !replacingOnline
        if (onlineAllowed && EpisodeTextResolver(graph.db).online(id) != null) return
        if (!allowOnline && old?.lookupOnline == true) dao.putTranscriptAt(old.copy(lookupOnline = false), TranscriptWriteSite.PREPARE_LOOKUP)
        // A local revision continues with its own model and language: new settings apply to new
        // transcripts, and replacing saved text takes an explicit Transcribe locally again (#228).
        if (!forceLocal && old != null && old.state != "stale" && old.fingerprint == ep.mediaFingerprint &&
            (old.source == "publisher" && old.state != "partial" || old.source == "whisper")) {
            if (old.state == "ready") return
            if (old.state in listOf("processing", "queued") && live.value[id]?.let { it.revision == old.revision && it.stage != TranscriptStage.QUEUED } == true) {
                if (requested) request(old.revision)
                return
            }
            // Exhaustion can publish paused before the old worker delivers its result.
            // Finish cancellation in WorkManager before KEEP sees this unique name again.
            // Scope to this revision: another revision's work must remain untouched.
            if (old.state in listOf("paused", "failed")) withContext(Dispatchers.IO) {
                manager.cancelAllWorkByTag(revisionTag(old)).result.get()
            }
            live.update { it - id }
            val current = continuing(old, prefs).copy(state = "queued", error = null, lookupOnline = onlineAllowed && prefs.onlineTranscripts)
            dao.putTranscriptAt(current, TranscriptWriteSite.PREPARE_EXISTING)
            if (requested) request(current.revision)
            dispatch(current); return
        }
        live.update { it - id }
        manager.cancelAllWorkByTag("transcript:$id")
        running[id]?.cancel()
        old?.let { leftQueue(it.revision) }
        val publisher = !forceLocal && !ep.transcriptUrl.isNullOrBlank() && !(old?.source == "publisher" && old.state == "partial")
        val current = TranscriptEntity(id, UUID.randomUUID().toString(), ep.mediaFingerprint, if (publisher) "publisher" else prefs.model,
            prefs.language, if (publisher) "publisher" else "whisper", durationMs = ep.durationMs, lookupOnline = onlineAllowed && prefs.onlineTranscripts, replaceOnline = replacingOnline || !allowOnline)
        // An explicit local replacement keeps the current text readable until it is ready (#229):
        // the first replaced transcript stays, an unfinished replacement in between does not.
        val kept = graph.db.withTransaction {
            val kept = if (!forceLocal) null else dao.previousTranscript(id)?.takeIf { it.matches(ep) }
                ?: old?.takeIf { it.source != "online" && it.state in KEPT_STATES && it.processedMs > 0 && it.fingerprint == ep.mediaFingerprint }
                    ?.let(PreviousTranscriptEntity::of)
            if (kept == null) dao.deleteTranscriptText(id)
            else { dao.deleteSegmentsExcept(id, kept.revision); dao.deleteCoverageExcept(id, kept.revision); dao.putPreviousTranscript(kept) }
            dao.putTranscriptAt(current, TranscriptWriteSite.PREPARE_NEW)
            kept
        }
        for(moment in dao.distillations().filter {it.episodeId==id && it.state=="waiting_transcript"}) {
            dao.momentAttempt(moment.attempt)?.let { attempt ->
                graph.diagnostics.event("transcription.dependency",moment.id,reason="transcript_required",related=current.revision,
                    correlation=graph.captures.correlation(attempt,current.revision))
            }
        }
        dao.analysis(id)?.takeIf { it.revision == old?.revision && old.source != "online" && it.revision != kept?.revision }?.let { dao.putAnalysis(it.copy(state = "stale", error = "Transcript changed. Analyze the current source again.")) }
        if (requested) request(current.revision)
        dispatch(current)
    }
    suspend fun pause(id: String) = controls.withLock {
        val active=live.value[id]
        live.update { it - id }
        dao.transcript(id)?.let {
            if (dao.transitionTranscriptState(id, it.revision, listOf(it.state), "paused", null) == 1)
                lifecycle("transcription.paused",it.revision,correlation=correlation(it.revision,active?.takeIf {run->run.revision==it.revision}?.run))
        }
        running[id]?.cancel()
        manager.cancelAllWorkByTag("transcript:$id")
    }
    /**
     * A newly ready model resumes the transcripts waiting for it, and those without saved chunks when it
     * is the selected model (#225). Saved chunks keep waiting for their own model (#228).
     */
    suspend fun resumeModel(id: String) {
        val selected = graph.settings.flow.first().model
        dao.transcripts().filter { it.state == "waiting_model" &&
            (it.model == id || id == selected && it.source == "whisper" && !hasSavedChunks(it)) }.forEach { prepare(it.episodeId) }
    }
    /** Selecting a model that is already downloaded resumes the transcripts that may switch to it. */
    suspend fun resumeSelectedModel() {
        val selected = graph.settings.flow.first().model
        if (modelReady(selected)) resumeModel(selected)
    }
    /** [refused]: Android refused to start the foreground session, so the queued rows wait for that, not their turn. */
    suspend fun recover(refused: Boolean = false) {
        if (!TranscriptionService.isRunning) pending().forEach { enqueue(it, nextStart(it) ?: 0, refused) }
    }
    /**
     * Earlier versions paused a transcript after five refused background starts (#56). That pause was not
     * the user's, so startup queues it again from its saved chunks (#301), as Resume would. A row paused
     * by the user, or one whose audio changed since (resuming it would start a new revision), stays put.
     */
    suspend fun resumeBlocked() {
        for (blocked in dao.transcripts().filter { it.state == "paused" && it.error == LEGACY_TRANSCRIPTION_BLOCKED_MESSAGE }) try {
            controls.withLock {
                val row = dao.transcript(blocked.episodeId)?.takeIf { it.revision == blocked.revision && it.state == "paused" &&
                    it.error == LEGACY_TRANSCRIPTION_BLOCKED_MESSAGE && it.source in listOf("whisper", "publisher") } ?: return@withLock
                if (dao.episode(row.episodeId)?.mediaFingerprint != row.fingerprint) return@withLock
                prepareInternal(row.episodeId, forceLocal = false, allowOnline = true, requested = false)
            }
        } catch (error: Exception) {
            // One row's failure leaves it paused for the user; the others still resume.
            if (error is CancellationException) throw error
            graph.diagnostics.failure("startup", error)
        }
    }
    suspend fun resumeQueued() { pending().firstOrNull()?.let { dispatch(it) } }
    private suspend fun pending() = transcriptQueueOrder(dao.transcripts().filter { it.state in listOf("queued", "processing") }, requested)
    private suspend fun dispatch(row: TranscriptEntity) {
        if (!TranscriptionService.start(graph.context)) { enqueue(row, nextStart(row) ?: 0, refused = true); return }
        val reason = transcriptionWaitReason(graph.context, graph.settings.flow.first(), transcriptionNeedsNetwork(dao.episode(row.episodeId), row))
        // Another transcript's chunk is in progress: this one waits for at least that chunk.
        val behind = running.keys.any { it != row.episodeId }
        if (reason != null || behind) lifecycle("transcription.queued", row.revision, reason = reason ?: QUEUED_BEHIND, correlation = correlation(row.revision))
        else shownWaits.update { it - row.revision } // An earlier wait no longer applies: the session takes it next.
    }
    /** The next revision the foreground session prepares, in queue order; every other one records why it waits. */
    internal suspend fun nextForeground(): TranscriptEntity? {
        val prefs = graph.settings.flow.first()
        var next: TranscriptEntity? = null
        for (row in pending()) {
            val ep = dao.episode(row.episodeId) ?: continue
            val reason = transcriptionWaitReason(graph.context, prefs, transcriptionNeedsNetwork(ep, row))
            when {
                // Behind the chosen one; its own constraints, if any, are still what it waits for.
                next != null -> lifecycle("transcription.queued", row.revision, reason = reason ?: QUEUED_BEHIND, correlation = correlation(row.revision))
                reason == null -> next = row
                else -> enqueue(row, nextStart(row) ?: 0)
            }
        }
        return next
    }
    internal suspend fun pausePending(message: String) {
        for (row in pending()) {
            pause(row.episodeId)
            dao.transcript(row.episodeId)?.takeIf { it.revision == row.revision && it.state == "paused" }
                ?.let { dao.transitionTranscriptState(it.episodeId, it.revision, listOf("paused"), "paused", message) }
        }
    }

    /**
     * Queued requests follow the current network and "Only transcribe while charging" settings (#301), in
     * place: the request keeps its id, delay and tags. A chunk already running finishes first.
     */
    internal suspend fun configureNetworkPolicy() {
        val hasLegacy = withContext(Dispatchers.IO) {
            manager.getWorkInfosByTag(TranscriptWorker::class.java.name).get().any { isLegacy(it) }
        }
        // Migration may replace legacy requests, so let the active chunk finish
        // first. This lock also keeps newly-starting workers from becoming writers
        // between the WorkInfo snapshot and cancellation. Modern updates do not wait.
        if (hasLegacy) processing.withLock { reconcileNetworkPolicy() }
        else reconcileNetworkPolicy()
    }

    private fun isLegacy(info: WorkInfo) = !info.state.isFinished &&
        info.tags.none { it.startsWith("transcript-revision:") }

    private suspend fun reconcileNetworkPolicy() = controls.withLock { scheduling.withLock { withContext(Dispatchers.IO) {
        val prefs = graph.settings.flow.first()
        for (row in pending()) {
            val ep = dao.episode(row.episodeId) ?: continue
            val network = if (!transcriptionNeedsNetwork(ep, row)) NetworkType.NOT_REQUIRED
                else if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val infos = manager.getWorkInfosByTag("transcript:${row.episodeId}").get()
            val revisionTag = revisionTag(row)
            // WorkInfo exposes neither input data nor unique names. Legacy requests
            // cannot safely be assigned the current revision: cancel them instead.
            // Room retains completed chunks; a single recovery request resumes them.
            val legacy = infos.filter { isLegacy(it) }
            for (info in legacy) manager.cancelWorkById(info.id).result.get()
            val current = infos.filter { !it.state.isFinished && revisionTag in it.tags }
            if (legacy.isNotEmpty() && current.isEmpty()) enqueueLocked(row, nextStart(row) ?: 0)
            for (info in current) {
                if (info.state.isFinished || info.constraints.requiredNetworkType == network &&
                    info.constraints.requiresCharging() == prefs.chargingOnly) continue
                val request = OneTimeWorkRequestBuilder<TranscriptWorker>().setId(info.id)
                    .setInputData(workDataOf("episode" to row.episodeId, "revision" to row.revision))
                    .setConstraints(info.constraints.withNetworkType(network, charging = prefs.chargingOnly))
                    .setInitialDelay(info.initialDelayMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .apply { info.tags.forEach { addTag(it) } }.build()
                manager.updateWork(request).get()
            }
        }
    } } }

    private fun revisionTag(row: TranscriptEntity) = "transcript-revision:${row.episodeId}:${row.revision}"

    private suspend fun enqueue(row: TranscriptEntity, start: Long, refused: Boolean = false) = scheduling.withLock { withContext(Dispatchers.IO) {
        enqueueLocked(row, start, refused)
    } }

    private suspend fun enqueueLocked(row: TranscriptEntity, start: Long, refused: Boolean = false) {
        val prefs = graph.settings.flow.first()
        val ep = dao.episode(row.episodeId) ?: return
        val needNetwork = transcriptionNeedsNetwork(ep, row)
        lifecycle("transcription.queued",row.revision,reason=transcriptionWaitReason(graph.context,prefs,needNetwork) ?: if (refused) "restricted" else "scheduled",correlation=correlation(row.revision))
        val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresCharging(prefs.chargingOnly)
            .apply { if (needNetwork) setRequiredNetworkType(if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED) }.build()
        manager.enqueueUniqueWork("transcript:${row.episodeId}:${row.revision}:$start", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<TranscriptWorker>().setInputData(workDataOf("episode" to row.episodeId, "revision" to row.revision))
                .setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS).addTag("transcript:${row.episodeId}").addTag(revisionTag(row)).build()).result.get()
    }
    private suspend fun nextStart(row: TranscriptEntity): Long? {
        if (row.durationMs <= 0) return 0
        val covered = dao.coverage(row.episodeId, row.revision).associateBy { it.startMs }
        fun missing(start: Long) = (covered[start]?.endMs ?: 0) < minOf(start + CHUNK_MS, row.durationMs) - 1
        val pending = dao.distillations().filter { it.episodeId == row.episodeId && it.state == "waiting_transcript" }
        for (saved in pending) {
            val window = dao.momentAttempt(saved.attempt)?.let { MomentGeneration.window(it.contextJson) } ?: ContextWindow.STANDARD
            val context = MomentContexts.range(saved.endMs, row.durationMs, window)
            val first = context.startMs / CHUNK_MS * CHUNK_MS
            val last = context.endMs.coerceAtMost(row.durationMs - 1).coerceAtLeast(0) / CHUNK_MS * CHUNK_MS
            var start = first
            while (start <= last) { if (missing(start)) return start; start += CHUNK_MS }
        }
        var start = 0L
        while (start < row.durationMs) { if (missing(start)) return start; start += CHUNK_MS }
        return null
    }

    suspend fun process(id: String, revision: String, worker: CoroutineWorker) = process(id, revision, TranscriptExecution(
        { worker.isStopped }, { worker.setForeground(workForeground(graph.context, worker.id, "Preparing transcript", true)) }, true))

    internal suspend fun process(id: String, revision: String, execution: TranscriptExecution) = processing.withLock {
        val job = currentCoroutineContext().job
        running[id] = job
        // Show automation observes transcript state changes, not each chunk (#140).
        try { processChunk(id, revision, execution) } finally { running.remove(id, job) }
    }

    private suspend fun processChunk(id: String, revision: String, execution: TranscriptExecution): TranscriptOutcome {
        var outcome = TranscriptOutcome.COMPLETED
        val executionJob = currentCoroutineContext().job
        val run = UUID.randomUUID().toString()
        // Pause and initial progress publication share the same lock. A queued pass
        // must not republish STARTING after pause has cleared the live estimate.
        var row = controls.withLock {
            val current=dao.transcript(id) ?: return@withLock null
            if(current.revision!=revision || current.state !in listOf("queued","processing"))return@withLock null
            // The parent, revision or user state can change after the suspended read.
            // A conditional update cannot recreate deleted work or rewind its checkpoint.
            if (dao.beginTranscriptProcessing(id, revision) != 1) return@withLock null
            lifecycle("transcription.started",revision,attempt=run,correlation=correlation(revision,run))
            live.update {it+(id to TranscriptProgress(revision,run,TranscriptStage.STARTING,current.processedMs,current.durationMs))}
            current
        } ?: return TranscriptOutcome.COMPLETED
        var lastDiagnosticStage: TranscriptStage? = null
        fun report(stage: TranscriptStage, workedMs: Long? = null, durationMs: Long? = null, start: Long? = null, end: Long? = null) {
            if(stage!=lastDiagnosticStage) {lastDiagnosticStage=stage;graph.diagnostics.event("transcription.stage",revision,reason=stage.name.lowercase(),correlation=correlation(revision,run))}
            live.update { all -> val current = all[id]
                if (current?.run != run) all else all + (id to current.copy(stage = stage, workedMs = workedMs ?: current.workedMs,
                    durationMs = durationMs ?: current.durationMs, chunkStartMs = start ?: current.chunkStartMs, chunkEndMs = end ?: current.chunkEndMs))
            }
        }
        var onlineCompleted=false
        try {
            currentCoroutineContext().ensureActive()
            if (execution.stopped()) throw CancellationException("Transcription execution stopped")
            try { execution.foreground() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                // A late failed foreground future must not touch a newly resumed revision.
                currentCoroutineContext().ensureActive()
                if (!error.isForegroundStartRejected()) throw error
                // Android 12+ refuses the service when WorkManager starts this job in the background, and a
                // retry would be refused the same way. Like an analysis (#84) or a download (#292), this
                // chunk runs as ordinary work without the notification (#301). A stop before it is saved
                // leaves the revision queued and WorkManager reruns the request from the saved chunks.
                graph.diagnostics.event("transcription.stage",revision,reason="restricted",correlation=correlation(revision,run))
            }
            // A pause, replacement or completion while the start was pending ends this pass.
            dao.transcript(id)?.takeIf { it.revision == revision && it.state in listOf("queued", "processing") } ?: return TranscriptOutcome.COMPLETED
            val initialEp = dao.episode(id) ?: return TranscriptOutcome.COMPLETED
            if (row.source == "publisher") {
                report(TranscriptStage.PUBLISHER)
                val published = try { published(initialEp, row) } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    false
                }
                if (published) { graph.captures.transcriptUpdated(id); return TranscriptOutcome.COMPLETED }
                val prefs = graph.settings.flow.first()
                row = row.copy(source = "whisper", model = prefs.model, language = prefs.language)
                dao.putTranscriptAt(row, TranscriptWriteSite.PUBLISHER_FALLBACK)
            }
            if (row.lookupOnline) {
                report(TranscriptStage.ONLINE)
                if (onlineLookup(initialEp, row)) {onlineCompleted=true;return TranscriptOutcome.COMPLETED}
                currentCoroutineContext().ensureActive()
                val current = dao.transcript(id) ?: return TranscriptOutcome.COMPLETED
                if (current.revision != revision || current.state in listOf("paused", "stale")) return TranscriptOutcome.COMPLETED
                row = current.copy(lookupOnline = false)
                dao.putTranscriptAt(row, TranscriptWriteSite.ONLINE_LOOKUP)
            }
            val modelPath = modelFile(row.model)
            if (dao.model(row.model)?.state != "ready" || !modelPath.isFile) {
                // Cancellation alone cannot retract an already-dispatched database write.
                // Serialize with pause/prepare and never restore the snapshot held by this pass.
                controls.withLock {
                    val current = dao.transcript(id)
                    if (current?.revision == revision && current.state in listOf("queued", "processing")) {
                        dao.transitionTranscriptState(id, revision, listOf("queued", "processing"), "waiting_model",
                            "Download the selected transcription model in Settings.")
                    }
                }
                return TranscriptOutcome.COMPLETED
            }
            report(TranscriptStage.DOWNLOADING)
            return graph.media.withDownloadedAudio(id) audio@{ source ->
                val ep = dao.episode(id) ?: return@audio TranscriptOutcome.COMPLETED
                if (row.fingerprint != ep.mediaFingerprint) { row = row.copy(fingerprint = ep.mediaFingerprint); dao.putTranscriptAt(row, TranscriptWriteSite.AUDIO_FINGERPRINT) }
                graph.activeFiles.acquire(modelPath)
                var temporaryWave: File? = null
                try {
                    val wave = File.createTempFile("transcription-", ".wav", graph.context.cacheDir)
                        .also { temporaryWave = it }
                    report(TranscriptStage.CHECKING_MODEL)
                    val model = graph.media.verifiedModel(row.model)
                    val start = nextStart(row) ?: run {
                        publishCompleted(id, revision, null, row)
                        graph.captures.transcriptUpdated(id); return@audio TranscriptOutcome.COMPLETED
                    }
                    val decodeStart = (start - 2000).coerceAtLeast(0)
                    report(TranscriptStage.DECODING, start = start, end = start + CHUNK_MS)
                    val decoded = withContext(Dispatchers.IO) { AudioClipDecoder().decodeChunk(source, wave, decodeStart, CHUNK_MS + start - decodeStart, execution.stopped) }
                    val duration = decoded.sourceDurationMs
                    require(duration > 0) { "This source has no reliable duration. Use a timed publisher transcript or another audio source." }
                    dao.duration(id, duration)
                    val end = minOf(start + CHUNK_MS, duration)
                    val samples = withContext(Dispatchers.IO) { readWhisperWave(wave.readBytes()) }
                    val saved = dao.coverage(id, revision)
                    val savedMs = saved.sumOf { it.endMs - it.startMs }
                    val savedInChunk = saved.filter { it.startMs == start }.sumOf { it.endMs - it.startMs }
                    report(TranscriptStage.STARTING, workedMs = savedMs, durationMs = duration, start = start, end = end)
                    val text = nativeTranscribe(model, samples, row.language, graph.activeFiles) { percent ->
                        report(TranscriptStage.TRANSCRIBING, transcribingAudioMs(savedMs, savedInChunk, start, end,
                            decoded.startMs, decoded.endMs, percent).coerceAtMost(duration))
                    }
                    report(TranscriptStage.SAVING)
                    val raw = JSONArray(text)
                    val previous = dao.window(id, row.revision, (start - 6000).coerceAtLeast(0), start).joinToString(" ") { it.text }
                    var first = true
                    val segments = (0 until raw.length()).mapNotNull { index ->
                        val segment = raw.getJSONObject(index)
                        val a = decoded.startMs + segment.getLong("startMs")
                        val b = decoded.startMs + segment.getLong("endMs")
                        if (b <= start || a >= end) return@mapNotNull null
                        var body = segment.getString("text").trim()
                        if (first && a < start) body = trimRepeatedOverlap(previous, body)
                        first = false
                        if (body.isBlank()) null else SegmentEntity(id, row.revision, a.coerceAtLeast(start), b.coerceAtMost(end).coerceAtLeast(a.coerceAtLeast(start)), body, index)
                    }
                    graph.db.withTransaction {
                        currentCoroutineContext().ensureActive()
                        val current = dao.transcript(id) ?: return@withTransaction
                        if (current.revision != revision || current.state in listOf("paused", "stale") || dao.episode(id)?.mediaFingerprint != ep.mediaFingerprint) return@withTransaction
                        dao.putSegments(segments)
                        // Actual EOS can leave a small codec-padding tail in container duration.
                        // It has been decoded and contains no further speech; do not rerun that tail.
                        val coverageEnd = if (decoded.sourceEnded && duration - decoded.endMs in 0..1000) end else minOf(end, decoded.endMs + 1)
                        require(coverageEnd > start) { "The audio ended before this window could be decoded. Completed chunks are saved." }
                        dao.putCoverage(CoverageEntity(id, revision, start, coverageEnd))
                        val processed = dao.coverage(id, revision).sumOf { it.endMs - it.startMs }
                        dao.putTranscriptAt(current.copy(fingerprint = ep.mediaFingerprint, durationMs = duration, processedMs = processed, state = "queued", error = null), TranscriptWriteSite.CHUNK_PROGRESS)
                    }
                    graph.captures.transcriptUpdated(id)
                    val current = dao.transcript(id) ?: return@audio TranscriptOutcome.COMPLETED
                    if (current.revision == revision && current.state !in listOf("paused", "stale")) {
                        val next = nextStart(current)
                        require(next != start) { "This audio window could not be decoded completely. Completed chunks are saved." }
                        if (next == null) publishCompleted(id, revision, duration, current)
                        if(next!=null && execution.reschedule) enqueue(current, next)
                    }
                } finally {
                    try { temporaryWave?.delete() } finally { graph.activeFiles.release(modelPath) }
                }
                TranscriptOutcome.COMPLETED
            }
        } catch (error: Exception) {
            withContext(NonCancellable) {
                controls.withLock {
                    val current = dao.transcript(id)
                    if (current?.revision == revision && current.state in listOf("ready", "partial") &&
                        error !is CancellationException) graph.diagnostics.failure("worker", error)
                    // Never restore the worker snapshot after pause, replacement or completion.
                    if (current?.revision == revision && current.state in listOf("queued", "processing")) {
                        when {
                            // Use the original Job, not this NonCancellable cleanup context.
                            // Pause/Resume can win while this catch waits for the control lock.
                            // A stop WorkManager reruns returns the row to queued. After a refused start WorkManager
                            // 2.10 ignores the scheduler's own stops (#301); a killed process never reaches this.
                            error is CancellationException || !executionJob.isActive ->
                                dao.transitionTranscriptState(id, revision, listOf("queued", "processing"), "queued", null)
                            else -> {
                                if (dao.transitionTranscriptState(id, revision, listOf("queued", "processing"), "failed", safeFailure(error)) == 1) {
                                    outcome = TranscriptOutcome.FAILED
                                    leftQueue(revision)
                                    graph.diagnostics.failure("transcription", error, revision, correlation = correlation(revision, run))
                                }
                            }
                        }
                    }
                }
            }
            if (error is CancellationException) throw error
            executionJob.ensureActive()
        } finally {
            withContext(NonCancellable) {
                val current = dao.transcript(id)
                if(onlineCompleted || current?.revision==revision) {
                    when {
                        onlineCompleted || current?.state=="ready" -> lifecycle("transcription.completed",revision,correlation=correlation(revision,run))
                        current?.state=="partial" -> lifecycle("transcription.partial",revision,reason="partial",correlation=correlation(revision,run))
                        current?.state=="waiting_model" -> lifecycle("transcription.queued",revision,reason="checking_model",correlation=correlation(revision,run))
                        current?.state=="stale" -> lifecycle("transcription.cancelled",revision,reason="superseded",correlation=correlation(revision,run))
                    }
                    if(current!=null && current.state in listOf("ready","partial"))graph.diagnostics.transcriptQuality(revision,current.source,current.model,current.language,
                        current.durationMs,current.processedMs,dao.segments(id,current.revision).isEmpty())
                } else if(current!=null)lifecycle("transcription.cancelled",revision,reason="superseded",correlation=correlation(revision,run))
                if (current?.revision == revision && current.state == "queued") {
                    val ep=dao.episode(id)
                    val needsNetwork=transcriptionNeedsNetwork(ep,current)
                    lifecycle("transcription.queued",revision,reason=transcriptionWaitReason(graph.context,graph.settings.flow.first(),needsNetwork) ?: "scheduled",correlation=correlation(revision,run))
                    report(TranscriptStage.QUEUED,current.processedMs,current.durationMs)
                }
                else live.update { if (it[id]?.run == run) it - id else it }
            }
        }
        return outcome
    }

    /** A fully covered local revision becomes the episode's ready transcript. */
    internal suspend fun publishCompleted(id: String, revision: String, processedMs: Long?, snapshot: TranscriptEntity? = null) = graph.db.withTransaction {
        val row = snapshot ?: dao.transcript(id)?.takeIf { it.revision == revision } ?: return@withTransaction
        dao.putTranscriptAt(row.copy(state = "ready", processedMs = processedMs ?: row.processedMs),
            if (processedMs == null) TranscriptWriteSite.CHUNK_COMPLETE else TranscriptWriteSite.TRANSCRIPT_COMPLETE)
        dao.deselectOnline(id)
        retireReplaced(id, revision)
    }

    /** The ready replacement takes over: the text kept readable meanwhile, and analysis of it, retire (#229). */
    private suspend fun retireReplaced(id: String, revision: String) {
        val previous = dao.previousTranscript(id)?.takeIf { it.revision != revision } ?: return
        dao.deleteSegmentsExcept(id, revision); dao.deleteCoverageExcept(id, revision); dao.deletePreviousTranscript(id)
        dao.analysis(id)?.takeIf { it.revision == previous.revision }
            ?.let { dao.putAnalysis(it.copy(state = "stale", error = "Transcript changed. Analyze the current source again.")) }
    }

    private suspend fun published(ep: EpisodeEntity, row: TranscriptEntity): Boolean {
        val url = ep.transcriptUrl ?: return false
        val input = withContext(Dispatchers.IO) { graph.http.newCall(Request.Builder().url(publicHttpUrl(url)).build()).execute().use { response ->
            check(response.isSuccessful); response.body!!.boundedText()
        } }
        val segments = if (ep.transcriptType?.contains("json") == true || input.trimStart().startsWith("{")) {
            val json = JSONObject(input).getJSONArray("segments")
            (0 until json.length()).map { i -> val s = json.getJSONObject(i)
                SubtitleSegment(TimeRange((s.getDouble("startTime") * 1000).toLong(), (s.getDouble("endTime") * 1000).toLong()), plainDescription(s.optString("body", s.optString("text")))) }
        } else parseVtt(input)
        require(segments.isNotEmpty() && segments.size <= 30000)
        val end = segments.maxOf { it.range.endMs }
        require(end > 0 && (ep.durationMs == 0L || end <= ep.durationMs + 60000))
        val duration = ep.durationMs.takeIf { it > 0 } ?: end
        graph.db.withTransaction {
            val current = dao.transcript(ep.id) ?: return@withTransaction
            if (current.revision != row.revision || current.state == "paused") return@withTransaction
            dao.putSegments(segments.mapIndexedNotNull { index, s -> if (s.range.startMs >= duration) null else SegmentEntity(ep.id, row.revision, s.range.startMs, s.range.endMs.coerceAtMost(duration), s.text, index) })
            dao.putCoverage(CoverageEntity(ep.id, row.revision, 0, minOf(end, duration)))
            dao.putTranscriptAt(current.copy(source = "publisher", model = "publisher", state = if (end >= duration) "ready" else "partial", durationMs = duration, processedMs = minOf(end, duration), error = null), TranscriptWriteSite.PUBLISHER_COMPLETE)
            if (end >= duration) retireReplaced(ep.id, row.revision)
        }
        return true
    }
}

private val nativeExecutor = Executors.newSingleThreadExecutor { Thread(it, "distillpod-transcription").apply { isDaemon = true } }
private suspend fun nativeTranscribe(model: File, samples: FloatArray, language: String, active: ActiveFiles, onProgress: (Int) -> Unit): String = suspendCancellableCoroutine { continuation ->
    val engine = NativeWhisper()
    active.acquire(model)
    nativeExecutor.submit {
        try { continuation.resume(engine.transcribe(model.absolutePath, samples, language) { if (continuation.isActive) onProgress(it) }) }
        catch (error: Exception) { continuation.resumeWithException(error) }
        finally { active.release(model) }
    }
    continuation.invokeOnCancellation { engine.cancel() }
}

class TranscriptWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.appGraph.ready.await()
        val episode = inputData.getString("episode") ?: return Result.failure()
        val revision = inputData.getString("revision") ?: return Result.failure()
        // A direct foreground session owns all its chunks. Do not spend job quota
        // waiting for its native-inference mutex or create another foreground service.
        if (TranscriptionService.isRunning) {
            TranscriptionService.start(applicationContext)
            val row = applicationContext.appGraph.db.library().transcript(episode)
            // Retain the constrained recovery request until the foreground owner
            // finishes. Otherwise a constraint change during service shutdown can
            // lose its replacement to ExistingWorkPolicy.KEEP while this job exits.
            return if (row?.revision == revision && row.state in listOf("queued", "processing")) Result.retry() else Result.success()
        }
        return applicationContext.appGraph.transcription.process(episode, revision, this).workerResult()
    }
}
class CaptureWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.appGraph
        graph.ready.await()
        val id = inputData.getString("capture") ?: return Result.failure()
        // Android reports a worker's stop reason from API 31; earlier versions record it as unknown.
        val reason = { if (android.os.Build.VERSION.SDK_INT >= 31) stopReason else androidx.work.WorkInfo.STOP_REASON_UNKNOWN }
        graph.captures.run(id, stopReason = reason, stop = {
            // A lost network constraint (a car's Wi-Fi to mobile handover) stops the worker and WorkManager
            // reruns it; only Pause cancels it for good (#261).
            if (!isStopped) dev.distillpod.android.work.WorkStop.ENDED
            else graph.jobs.stopped("capture:$id", this.id, if (android.os.Build.VERSION.SDK_INT >= 31) stopReason else null)
        })
        return Result.success()
    }
}
