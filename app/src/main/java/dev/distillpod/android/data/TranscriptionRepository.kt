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
enum class TranscriptStage { ONLINE, PUBLISHER, DOWNLOADING, CHECKING_MODEL, DECODING, STARTING, TRANSCRIBING, SAVING, QUEUED }
data class TranscriptProgress(val revision: String, val run: String, val stage: TranscriptStage,
    val workedMs: Long, val durationMs: Long, val chunkStartMs: Long = 0, val chunkEndMs: Long = 0)

internal class TranscriptExecution(val stopped: () -> Boolean, val foreground: suspend () -> Unit, val reschedule: Boolean)

class TranscriptionRepository(private val graph: AppGraph,
    private val onlineLookup: suspend (EpisodeEntity, TranscriptEntity) -> Boolean = { ep, row -> graph.onlineTranscripts.lookup(ep, row) }) {
    private val dao = graph.db.library()
    private val manager get() = WorkManager.getInstance(graph.context)
    private val processing = Mutex()
    private val controls = Mutex()
    private val scheduling = Mutex()
    private val running = ConcurrentHashMap<String, Job>()
    private val live = MutableStateFlow<Map<String, TranscriptProgress>>(emptyMap())
    val progress = live.asStateFlow()
    private fun correlation(revision:String,run:String?=null):DiagnosticCorrelation =
        runCatching {DiagnosticCorrelation(transcriptRevisionId=revision,executionId=run)}.getOrDefault(DiagnosticCorrelation())
    suspend fun prepare(id: String, forceLocal: Boolean = false, allowOnline: Boolean = true) = controls.withLock { prepareInternal(id, forceLocal, allowOnline) }
    /** Playback must not undo a pause, retry a failure or regenerate text after a model change. */
    suspend fun prepareAutomatically(id: String) = controls.withLock {
        if (!graph.settings.flow.first().transcribeOnPlayback) return@withLock
        val ep = dao.episode(id) ?: return@withLock // Diagnostic clips are not library episodes.
        // Download completion updates the audio fingerprint before the active chunk adopts it.
        // A streaming-to-file playback transition must not replace that in-flight revision.
        if (running[id]?.isActive == true) return@withLock
        val old = dao.transcript(id)
        if (old?.state == "paused") return@withLock
        if (old != null && old.fingerprint == ep.mediaFingerprint && old.state != "stale") return@withLock
        prepareInternal(id, forceLocal = false, allowOnline = true)
    }

    private suspend fun prepareInternal(id: String, forceLocal: Boolean, allowOnline: Boolean) {
        val ep = dao.episode(id) ?: error("Episode is unavailable")
        val prefs = graph.settings.flow.first()
        val old = dao.transcript(id)
        val replacingOnline = forceLocal || old?.let { it.replaceOnline && it.state !in listOf("ready", "stale") } == true
        val onlineAllowed = allowOnline && !replacingOnline
        if (onlineAllowed && EpisodeTextResolver(graph.db).online(id) != null) return
        if (!allowOnline && old?.lookupOnline == true) dao.putTranscript(old.copy(lookupOnline = false))
        if (!forceLocal && old != null && old.state != "stale" && old.fingerprint == ep.mediaFingerprint &&
            (old.source == "publisher" && old.state != "partial" || old.model == prefs.model && old.language == prefs.language)) {
            if (old.state == "ready") return
            if (old.state in listOf("processing", "queued") && live.value[id]?.let { it.revision == old.revision && it.stage != TranscriptStage.QUEUED } == true) return
            live.update { it - id }
            val current = old.copy(state = "queued", error = null, lookupOnline = onlineAllowed && prefs.onlineTranscripts)
            dao.putTranscript(current); dispatch(current); return
        }
        live.update { it - id }
        manager.cancelAllWorkByTag("transcript:$id")
        running[id]?.cancel()
        val publisher = !forceLocal && !ep.transcriptUrl.isNullOrBlank() && !(old?.source == "publisher" && old.state == "partial")
        val current = TranscriptEntity(id, UUID.randomUUID().toString(), ep.mediaFingerprint, if (publisher) "publisher" else prefs.model,
            prefs.language, if (publisher) "publisher" else "whisper", durationMs = ep.durationMs, lookupOnline = onlineAllowed && prefs.onlineTranscripts, replaceOnline = replacingOnline || !allowOnline)
        graph.db.withTransaction { dao.deleteSegments(id); dao.deleteCoverage(id); dao.putTranscript(current) }
        for(moment in dao.distillations().filter {it.episodeId==id && it.state=="waiting_transcript"}) {
            dao.momentAttempt(moment.attempt)?.let { attempt ->
                graph.diagnostics.event("transcription.dependency",moment.id,reason="transcript_required",related=current.revision,
                    correlation=graph.captures.correlation(attempt,current.revision))
            }
        }
        dao.analysis(id)?.takeIf { it.revision == old?.revision && old.source != "online" }?.let { dao.putAnalysis(it.copy(state = "stale", error = "Transcript changed. Analyze the current source again.")) }
        dispatch(current)
    }
    suspend fun pause(id: String) = controls.withLock {
        val active=live.value[id]
        live.update { it - id }
        dao.transcript(id)?.let { dao.putTranscript(it.copy(state = "paused", error = null));graph.diagnostics.event("transcription.paused",it.revision,correlation=correlation(it.revision,active?.takeIf {run->run.revision==it.revision}?.run)) }
        running[id]?.cancel()
        manager.cancelAllWorkByTag("transcript:$id")
    }
    suspend fun resumeModel(id: String) { dao.transcripts().filter { it.model == id && it.state == "waiting_model" }.forEach { prepare(it.episodeId) } }
    suspend fun recover() {
        if (!TranscriptionService.isRunning) pending().forEach { enqueue(it, nextStart(it) ?: 0) }
    }
    suspend fun resumeQueued() { pending().firstOrNull()?.let { dispatch(it) } }
    private suspend fun pending() = dao.transcripts().filter { it.state in listOf("queued", "processing") }
    private suspend fun dispatch(row: TranscriptEntity) {
        if (!TranscriptionService.start(graph.context)) enqueue(row, nextStart(row) ?: 0)
    }
    internal suspend fun nextForeground(): TranscriptEntity? {
        val prefs = graph.settings.flow.first()
        for (row in pending()) {
            val ep = dao.episode(row.episodeId) ?: continue
            val network = transcriptionNeedsNetwork(ep, row)
            if (transcriptionAllowed(graph.context, prefs, network)) return row
            enqueue(row, nextStart(row) ?: 0)
        }
        return null
    }
    internal suspend fun pausePending(message: String) {
        for (row in pending()) {
            pause(row.episodeId)
            dao.transcript(row.episodeId)?.takeIf { it.revision == row.revision && it.state == "paused" }
                ?.let { dao.putTranscript(it.copy(error = message)) }
        }
    }

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
                if (info.state.isFinished || info.constraints.requiredNetworkType == network) continue
                val request = OneTimeWorkRequestBuilder<TranscriptWorker>().setId(info.id)
                    .setInputData(workDataOf("episode" to row.episodeId, "revision" to row.revision))
                    .setConstraints(info.constraints.withNetworkType(network))
                    .setInitialDelay(info.initialDelayMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .apply { info.tags.forEach { addTag(it) } }.build()
                manager.updateWork(request).get()
            }
        }
    } } }

    private fun revisionTag(row: TranscriptEntity) = "transcript-revision:${row.episodeId}:${row.revision}"

    private suspend fun enqueue(row: TranscriptEntity, start: Long) = scheduling.withLock { withContext(Dispatchers.IO) {
        enqueueLocked(row, start)
    } }

    private suspend fun enqueueLocked(row: TranscriptEntity, start: Long) {
        val prefs = graph.settings.flow.first()
        val ep = dao.episode(row.episodeId) ?: return
        val needNetwork = transcriptionNeedsNetwork(ep, row)
        graph.diagnostics.event("transcription.queued",row.revision,reason=transcriptionWaitReason(graph.context,prefs,needNetwork) ?: "scheduled",correlation=correlation(row.revision))
        val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresCharging(prefs.chargingOnly)
            .apply { if (needNetwork) setRequiredNetworkType(if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED) }.build()
        manager.enqueueUniqueWork("transcript:${row.episodeId}:${row.revision}:$start", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<TranscriptWorker>().setInputData(workDataOf("episode" to row.episodeId, "revision" to row.revision))
                .setConstraints(constraints).addTag("transcript:${row.episodeId}").addTag(revisionTag(row)).build()).result.get()
    }
    private suspend fun nextStart(row: TranscriptEntity): Long? {
        if (row.durationMs <= 0) return 0
        val covered = dao.coverage(row.episodeId, row.revision).associateBy { it.startMs }
        fun missing(start: Long) = (covered[start]?.endMs ?: 0) < minOf(start + CHUNK_MS, row.durationMs) - 1
        val pending = dao.distillations().filter { it.episodeId == row.episodeId && it.state == "waiting_transcript" }
        for (saved in pending) {
            val expanded = dao.momentAttempt(saved.attempt)?.let { JSONObject(it.contextJson).optBoolean("expanded") } ?: false
            val context = MomentContexts.range(saved.endMs, row.durationMs, expanded)
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
        try { processChunk(id, revision, execution) } finally { running.remove(id, job); graph.automation.kick() }
    }

    private suspend fun processChunk(id: String, revision: String, execution: TranscriptExecution) {
        val run = UUID.randomUUID().toString()
        // Pause and initial progress publication share the same lock. A queued pass
        // must not republish STARTING after pause has cleared the live estimate.
        var row = controls.withLock {
            val current=dao.transcript(id) ?: return@withLock null
            if(current.revision!=revision || current.state in listOf("paused","ready","stale"))return@withLock null
            dao.putTranscript(current.copy(state="processing",error=null))
            graph.diagnostics.event("transcription.started",revision,attempt=run,correlation=correlation(revision,run))
            live.update {it+(id to TranscriptProgress(revision,run,TranscriptStage.STARTING,current.processedMs,current.durationMs))}
            current
        } ?: return
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
            execution.foreground()
            val initialEp = dao.episode(id) ?: return
            if (row.source == "publisher") {
                report(TranscriptStage.PUBLISHER)
                val published = try { published(initialEp, row) } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    false
                }
                if (published) { graph.captures.transcriptUpdated(id); return }
                val prefs = graph.settings.flow.first()
                row = row.copy(source = "whisper", model = prefs.model, language = prefs.language)
                dao.putTranscript(row)
            }
            if (row.lookupOnline) {
                report(TranscriptStage.ONLINE)
                if (onlineLookup(initialEp, row)) {onlineCompleted=true;return}
                currentCoroutineContext().ensureActive()
                val current = dao.transcript(id) ?: return
                if (current.revision != revision || current.state in listOf("paused", "stale")) return
                row = current.copy(lookupOnline = false)
                dao.putTranscript(row)
            }
            val modelPath = File(graph.media.modelsDirectory, "${row.model}.bin")
            if (dao.model(row.model)?.state != "ready" || !modelPath.isFile) {
                // Cancellation alone cannot retract an already-dispatched database write.
                // Serialize with pause/prepare and never restore the snapshot held by this pass.
                controls.withLock {
                    val current = dao.transcript(id)
                    if (current?.revision == revision && current.state in listOf("queued", "processing")) {
                        dao.putTranscript(current.copy(state = "waiting_model",
                            error = "Download the selected transcription model in Settings."))
                    }
                }
                return
            }
            report(TranscriptStage.DOWNLOADING)
            val source = graph.media.ensureDownloaded(id)
            val ep = dao.episode(id) ?: return
            if (row.fingerprint != ep.mediaFingerprint) { row = row.copy(fingerprint = ep.mediaFingerprint); dao.putTranscript(row) }
            graph.activeFiles.acquire(source); graph.activeFiles.acquire(modelPath)
            val wave = File.createTempFile("transcription-", ".wav", graph.context.cacheDir)
            try {
                report(TranscriptStage.CHECKING_MODEL)
                val model = graph.media.verifiedModel(row.model)
                val start = nextStart(row) ?: run {
                    graph.db.withTransaction {
                        dao.putTranscript(row.copy(state = "ready"))
                        dao.deselectOnline(id)
                    }
                    graph.captures.transcriptUpdated(id); return
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
                    dao.putTranscript(current.copy(fingerprint = ep.mediaFingerprint, durationMs = duration, processedMs = processed, state = "queued", error = null))
                }
                graph.captures.transcriptUpdated(id)
                val current = dao.transcript(id) ?: return
                if (current.revision == revision && current.state !in listOf("paused", "stale")) {
                    val next = nextStart(current)
                    require(next != start) { "This audio window could not be decoded completely. Completed chunks are saved." }
                    if (next == null) {
                        graph.db.withTransaction {
                            dao.putTranscript(current.copy(state = "ready", processedMs = duration))
                            dao.deselectOnline(id)
                        }
                    }
                    if(next!=null && execution.reschedule) enqueue(current, next)
                }
            } finally { wave.delete(); graph.activeFiles.release(source); graph.activeFiles.release(modelPath) }
        } catch (error: Exception) {
            withContext(NonCancellable) {
                val current = dao.transcript(id)
                if(current?.state in listOf("ready","partial"))graph.diagnostics.failure("worker",error)
                else if(!(error is CancellationException && current?.state=="paused"))graph.diagnostics.failure("transcription",error,revision,correlation=correlation(revision,run))
                if (current?.revision == revision && current.state !in listOf("paused", "stale", "ready", "partial")) dao.putTranscript(current.copy(state = if (error is CancellationException) "queued" else "failed", error = if (error is CancellationException) null else safeFailure(error)))
            }
            if (error is CancellationException) throw error
        } finally {
            withContext(NonCancellable) {
                val current = dao.transcript(id)
                if(onlineCompleted || current?.revision==revision) {
                    when {
                        onlineCompleted || current?.state=="ready" -> graph.diagnostics.event("transcription.completed",revision,correlation=correlation(revision,run))
                        current?.state=="partial" -> graph.diagnostics.event("transcription.partial",revision,reason="partial",correlation=correlation(revision,run))
                        current?.state=="waiting_model" -> graph.diagnostics.event("transcription.queued",revision,reason="checking_model",correlation=correlation(revision,run))
                        current?.state=="stale" -> graph.diagnostics.event("transcription.cancelled",revision,reason="superseded",correlation=correlation(revision,run))
                    }
                    if(current!=null && current.state in listOf("ready","partial"))graph.diagnostics.transcriptQuality(revision,current.source,current.model,current.language,
                        current.durationMs,current.processedMs,dao.segments(id,current.revision).isEmpty())
                } else if(current!=null)graph.diagnostics.event("transcription.cancelled",revision,reason="superseded",correlation=correlation(revision,run))
                if (current?.revision == revision && current.state == "queued") {
                    val ep=dao.episode(id)
                    val needsNetwork=transcriptionNeedsNetwork(ep,current)
                    graph.diagnostics.event("transcription.queued",revision,reason=transcriptionWaitReason(graph.context,graph.settings.flow.first(),needsNetwork) ?: "scheduled",correlation=correlation(revision,run))
                    report(TranscriptStage.QUEUED,current.processedMs,current.durationMs)
                }
                else live.update { if (it[id]?.run == run) it - id else it }
            }
        }
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
            dao.putTranscript(current.copy(source = "publisher", model = "publisher", state = if (end >= duration) "ready" else "partial", durationMs = duration, processedMs = minOf(end, duration), error = null))
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
        applicationContext.appGraph.transcription.process(episode, revision, this)
        return Result.success()
    }
}
class CaptureWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result { applicationContext.appGraph.ready.await(); applicationContext.appGraph.captures.run(inputData.getString("capture") ?: return Result.failure()); return Result.success() }
}
