package dev.distillpod.android.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import dev.distillpod.android.AppGraph
import dev.distillpod.android.MainActivity
import dev.distillpod.android.appGraph
import dev.distillpod.android.data.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AppJobs(private val context: Context) {
    private val manager get() = WorkManager.getInstance(context)
    private val downloadControls = Mutex()
    suspend fun download(id: String) = downloadControls.withLock { withContext(Dispatchers.IO) {
        val prefs = context.appGraph.settings.flow.first()
        context.appGraph.db.library().episode(id)?.let { ep ->
            if (ep.downloadState == "downloaded" && ep.localFile?.let { java.io.File(it).isFile } == true) return@withContext
            context.appGraph.db.library().downloadProgress(id, "queued", ep.downloadedBytes, ep.totalBytes, ep.etag, ep.lastModified, null)
        }
        manager.enqueueUniqueWork("download:$id", ExistingWorkPolicy.KEEP, request("download", id, prefs)).result.get()
        Unit
    } }
    suspend fun model(id: String) = downloadControls.withLock { withContext(Dispatchers.IO) {
        val prefs = context.appGraph.settings.flow.first()
        val old = context.appGraph.db.library().model(id)
        context.appGraph.db.library().putModel(ModelEntity(id, "queued", old?.bytes ?: 0, old?.etag))
        manager.enqueueUniqueWork("model:$id", ExistingWorkPolicy.KEEP, request("model", id, prefs)).result.get()
        Unit
    } }
    /** Updates retain identity/checkpoints. A running attempt finishes under its original constraints. */
    suspend fun configureNetworkPolicy() = downloadControls.withLock { withContext(Dispatchers.IO) {
        val prefs = context.appGraph.settings.flow.first()
        for (info in manager.getWorkInfosByTag(LibraryWorker::class.java.name).get()) {
            if (info.state.isFinished) continue
            val tag = info.tags.firstOrNull { it.startsWith("download:") || it.startsWith("model:") } ?: continue
            val kind = tag.substringBefore(':')
            val target = tag.substringAfter(':')
            val network = if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            if (info.constraints.requiredNetworkType != network) manager.updateWork(request(kind, target, prefs, info)).get()
        }
        context.appGraph.transcription.configureNetworkPolicy()
        configureRefresh()
    } }
    fun refresh(id:String = "") { manager.enqueueUniqueWork(if(id.isBlank())"refresh" else "refresh:$id", ExistingWorkPolicy.KEEP, request("refresh", id, Preferences(wifiOnly = false))) }
    suspend fun configureRefresh() {
        val prefs = context.appGraph.settings.flow.first()
        if (prefs.refresh) manager.enqueueUniquePeriodicWork("refresh-periodic", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<LibraryWorker>(prefs.refreshHours.toLong(), TimeUnit.HOURS).setInputData(workDataOf("kind" to "refresh"))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()).build())
        else manager.cancelUniqueWork("refresh-periodic")
    }
    /** Persist the pause too: work waiting for its network or backoff never runs to record it. */
    suspend fun pauseDownload(id: String) = pause("download", id)
    suspend fun pauseModel(id: String) = pause("model", id)
    private suspend fun pause(kind: String, id: String) = downloadControls.withLock { withContext(Dispatchers.IO) {
        manager.cancelUniqueWork("$kind:$id").result.get()
        transition(kind, id, "paused", null)
        Unit
    } }
    /**
     * Only Pause (in the app or the notification) cancels a transfer request. Any other stop (network,
     * job timeouts, quota, the FGS time limit) is rescheduled by WorkManager, so the row stays resumable
     * unless the request was cancelled meanwhile. API 29-30 report no stop reason; WorkManager marks an
     * app cancellation before it stops the worker.
     */
    internal suspend fun stopped(kind: String, id: String, workId: java.util.UUID, stopReason: Int?) = withContext(NonCancellable + Dispatchers.IO) {
        downloadControls.withLock {
            val byApp = stopReason?.let { it == WorkInfo.STOP_REASON_CANCELLED_BY_APP }
                ?: (manager.getWorkInfoById(workId).get()?.state == WorkInfo.State.CANCELLED)
            if (byApp) transition(kind, id, "paused", null, PAUSABLE_TRANSFER_STATES)
            else if (manager.getWorkInfosForUniqueWork("$kind:$id").get().any { !it.state.isFinished })
                transition(kind, id, "queued", null, PAUSABLE_TRANSFER_STATES)
        }
        Unit
    }
    /** Settle an active transfer that no longer has work; never rewrites a pause, resume or completion. */
    internal suspend fun settle(kind: String, id: String, state: String, error: String?) =
        withContext(NonCancellable + Dispatchers.IO) { downloadControls.withLock { transition(kind, id, state, error) }; Unit }
    private suspend fun transition(kind: String, id: String, state: String, error: String?, from: List<String> = ACTIVE_TRANSFER_STATES) {
        val dao = context.appGraph.db.library()
        when (kind) {
            "download" -> dao.transitionDownload(id, from, state, error)
            "model" -> dao.transitionModel(id, from, state, error)
        }
    }
    /** Startup restores interrupted transfers; paused and failed rows wait for the user. */
    suspend fun recoverDownloads() {
        val dao = context.appGraph.db.library()
        dao.episodes().filter { it.downloadState in listOf("queued", "downloading") }.forEach { download(it.id) }
        dao.observeModels().first().filter { it.state in listOf("queued", "downloading") }.forEach { model(it.id) }
    }
    fun capture(id: String) { manager.enqueueUniqueWork("capture:$id", ExistingWorkPolicy.APPEND_OR_REPLACE,
        OneTimeWorkRequestBuilder<CaptureWorker>().setInputData(workDataOf("capture" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()) }
    fun cancelCapture(id: String) { manager.cancelUniqueWork("capture:$id") }
    fun analyze(id: String) { enqueueAi("analysis", id) }
    fun reply(id: String) { enqueueAi("reply", id) }
    fun explain(id: String) { enqueueAi("explain", id) }
    fun cancelExplanation(id: String) { manager.cancelUniqueWork("explain:$id") }
    fun cancelAnalysis(id: String) { manager.cancelUniqueWork("analysis:$id") }
    fun cancelReply(id: String) { manager.cancelUniqueWork("reply:$id") }
    fun recommend(request: String, topics: String, history: Boolean) { manager.enqueueUniqueWork("recommendations", ExistingWorkPolicy.APPEND_OR_REPLACE,
        OneTimeWorkRequestBuilder<RecommendationWorker>().setInputData(workDataOf("request" to request, "topics" to topics, "history" to history))
            .addTag("recommendation:$request").setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()) }
    fun recommendationPending(request: String): Boolean = manager.getWorkInfosForUniqueWork("recommendations").get(10, TimeUnit.SECONDS)
        .any { !it.state.isFinished && "recommendation:$request" in it.tags }
    fun cancelRecommendations() { manager.cancelUniqueWork("recommendations") }
    private fun enqueueAi(kind: String, id: String) { manager.enqueueUniqueWork("$kind:$id", ExistingWorkPolicy.APPEND_OR_REPLACE,
        OneTimeWorkRequestBuilder<UnderstandingWorker>().setInputData(workDataOf("kind" to kind, "id" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()) }
    private fun request(kind: String, id: String, prefs: Preferences, existing: WorkInfo? = null) = OneTimeWorkRequestBuilder<LibraryWorker>()
        .setInputData(workDataOf("kind" to kind, "id" to id))
        .setConstraints((existing?.constraints ?: Constraints.NONE)
            .withNetworkType(if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED))
        .apply { existing?.let { info ->
            setId(info.id)
            setInitialDelay(info.initialDelayMillis, TimeUnit.MILLISECONDS)
            info.tags.forEach { addTag(it) }
        } }
        .addTag("$kind:$id").build()
}

private val ACTIVE_TRANSFER_STATES = listOf("queued", "downloading")
/** The transfer records its own cancellation as paused before the worker classifies the stop. */
private val PAUSABLE_TRANSFER_STATES = ACTIVE_TRANSFER_STATES + "paused"
const val DOWNLOAD_BLOCKED_MESSAGE = "Android blocked the background download. Open DistillPod and tap Resume."
const val MODEL_BLOCKED_MESSAGE = "Android blocked the background model download. Open DistillPod and tap Resume model download."

class LibraryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.appGraph
        graph.ready.await()
        val kind = inputData.getString("kind")
        val target = inputData.getString("id") ?: ""
        return try {
            when (kind) {
                "download" -> {
                    startForeground("Downloading episode")?.let { return deferStart(graph, kind, target, it) }
                    graph.media.ensureDownloaded(target) { progress ->
                        runBlocking { setProgress(workDataOf("bytes" to progress.bytes, "total" to progress.total)) }
                    }
                }
                "model" -> {
                    startForeground("Downloading transcription model")?.let { return deferStart(graph, kind, target, it) }
                    graph.media.downloadModel(target) { progress ->
                        runBlocking { setProgress(workDataOf("bytes" to progress.bytes, "total" to progress.total)) }
                    }
                    graph.transcription.resumeModel(target)
                }
                "refresh" -> if(target.isBlank())graph.podcasts.refreshAll() else graph.podcasts.refresh(target)
                else -> return Result.failure()
            }
            graph.automation.kick()
            Result.success()
        } catch (cancelled: CancellationException) {
            if (kind == "download" || kind == "model")
                graph.jobs.stopped(kind, target, id, if (Build.VERSION.SDK_INT >= 31) stopReason else null)
            throw cancelled
        }
        catch (error: Exception) {
            graph.diagnostics.failure("worker", error)
            if (kind == "download" && error is java.io.IOException && runAttemptCount < 3) Result.retry()
            else {
                // A failure before the transfer starts must not leave the row queued without work.
                if (kind == "download" || kind == "model") graph.jobs.settle(kind, target, "failed", safeFailure(error))
                Result.failure()
            }
        }
    }

    /** Returns Android's refusal of a background foreground-service start; other errors propagate. */
    private suspend fun startForeground(title: String): Exception? = try {
        setForeground(workForeground(applicationContext, id, title))
        null
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        currentCoroutineContext().ensureActive()
        if (error.isForegroundStartRejected()) error else throw error
    }

    /** Like transcription (#56): retry while the row stays queued, then pause with an actionable message. */
    private suspend fun deferStart(graph: AppGraph, kind: String, target: String, error: Exception): Result {
        if (runAttemptCount < MAX_FOREGROUND_START_ATTEMPTS - 1) return Result.retry()
        graph.diagnostics.failure("worker", error)
        graph.jobs.settle(kind, target, "paused", if (kind == "model") MODEL_BLOCKED_MESSAGE else DOWNLOAD_BLOCKED_MESSAGE)
        return Result.failure()
    }
}

class UnderstandingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.appGraph
        graph.ready.await()
        val target = inputData.getString("id") ?: return Result.failure()
        when (inputData.getString("kind")) {
            "analysis" -> graph.understanding.runAnalysis(target)
            "reply" -> graph.understanding.runReply(target)
            "explain" -> graph.explanations.run(target)
            else -> return Result.failure()
        }
        graph.automation.kick()
        return Result.success()
    }
}
class RecommendationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.appGraph
        graph.ready.await()
        graph.recommendations.run(inputData.getString("request") ?: return Result.failure(), inputData.getString("topics") ?: return Result.failure(), inputData.getBoolean("history", false))
        return Result.success()
    }
}

fun workForeground(context: Context, workId: java.util.UUID, title: String, media: Boolean = false): ForegroundInfo {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("processing", "Downloads and transcription", NotificationManager.IMPORTANCE_LOW))
    val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification = NotificationCompat.Builder(context, "processing").setSmallIcon(android.R.drawable.ic_popup_sync)
        .setContentTitle(title).setContentText("DistillPod · tap to view progress").setContentIntent(intent).setOngoing(true)
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Pause", WorkManager.getInstance(context).createCancelPendingIntent(workId)).build()
    val type = if (media && Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    return ForegroundInfo(workId.hashCode() and Int.MAX_VALUE, notification, type)
}
