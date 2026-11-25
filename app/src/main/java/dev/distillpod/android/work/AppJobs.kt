package dev.distillpod.android.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
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
    } }
    fun refresh(id:String = "") { manager.enqueueUniqueWork(if(id.isBlank())"refresh" else "refresh:$id", ExistingWorkPolicy.KEEP, request("refresh", id, Preferences(wifiOnly = false))) }
    /**
     * Runs on every process start and Settings change. An unchanged schedule is kept as is; updating it
     * each time would bump the request generation and rewrite WorkManager state for nothing.
     * Feed checks are small and run on any network; the Wi-Fi / unmetered setting limits downloads only (#233).
     * A schedule registered by an earlier version with an unmetered constraint is updated in place.
     */
    suspend fun configureRefresh() {
        val prefs = context.appGraph.settings.flow.first()
        if (!prefs.refresh) { manager.cancelUniqueWork("refresh-periodic"); return }
        val interval = TimeUnit.HOURS.toMillis(prefs.refreshHours.toLong())
        val network = NetworkType.CONNECTED
        val current = withContext(Dispatchers.IO) { manager.getWorkInfosForUniqueWork("refresh-periodic").get() }.firstOrNull { !it.state.isFinished }
        val unchanged = current != null && current.periodicityInfo?.repeatIntervalMillis == interval &&
            current.constraints.requiredNetworkType == network
        manager.enqueueUniquePeriodicWork("refresh-periodic", if (unchanged) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<LibraryWorker>(interval, TimeUnit.MILLISECONDS).setInputData(workDataOf("kind" to "refresh"))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build()).build())
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
            when (classifyStop("$kind:$id", workId, stopReason)) {
                WorkStop.PAUSED -> transition(kind, id, "paused", null, PAUSABLE_TRANSFER_STATES)
                WorkStop.RESCHEDULED -> transition(kind, id, "queued", null, PAUSABLE_TRANSFER_STATES)
                WorkStop.ENDED -> {}
            }
        }
        Unit
    }
    /** Same rules for a stopped AI request (#84): Pause cancels it, any other stop is rerun by WorkManager. */
    internal suspend fun stopped(name: String, workId: java.util.UUID, stopReason: Int?): WorkStop =
        withContext(NonCancellable + Dispatchers.IO) { classifyStop(name, workId, stopReason) }
    private fun classifyStop(name: String, workId: java.util.UUID, stopReason: Int?): WorkStop {
        val byApp = stopReason?.let { it == WorkInfo.STOP_REASON_CANCELLED_BY_APP }
            ?: (manager.getWorkInfoById(workId).get()?.state == WorkInfo.State.CANCELLED)
        return when {
            byApp -> WorkStop.PAUSED
            manager.getWorkInfosForUniqueWork(name).get().any { !it.state.isFinished } -> WorkStop.RESCHEDULED
            else -> WorkStop.ENDED
        }
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
    /**
     * Startup restores interrupted transfers; paused and failed rows wait for the user. An earlier version
     * paused a transfer when Android refused its foreground start (#138). That pause was not the user's,
     * so those rows resume too, keeping their partial file and validators (#292).
     */
    suspend fun recoverDownloads() {
        val dao = context.appGraph.db.library()
        dao.episodes().filter { it.downloadState in listOf("queued", "downloading") ||
            it.downloadState == "paused" && it.error == DOWNLOAD_BLOCKED_MESSAGE }.forEach { download(it.id) }
        dao.observeModels().first().filter { it.state in listOf("queued", "downloading") ||
            it.state == "paused" && it.error == MODEL_BLOCKED_MESSAGE }.forEach { model(it.id) }
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

/** How a worker stop ends: Pause in the app or notification, a stop WorkManager reruns, or neither. */
enum class WorkStop { PAUSED, RESCHEDULED, ENDED }

private val ACTIVE_TRANSFER_STATES = listOf("queued", "downloading")
/** The transfer records its own cancellation as paused before the worker classifies the stop. */
private val PAUSABLE_TRANSFER_STATES = ACTIVE_TRANSFER_STATES + "paused"
/** Earlier versions paused a transfer with these when Android refused its foreground start; startup resumes it. */
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
                    val foreground = startForeground("Downloading episode")
                    transfer(graph, foreground) { blocked ->
                        graph.media.ensureDownloaded(target, blocked) { progress ->
                            runBlocking { setProgress(workDataOf("bytes" to progress.bytes, "total" to progress.total)) }
                        }
                    }
                }
                "model" -> {
                    val foreground = startForeground("Downloading transcription model")
                    transfer(graph, foreground) { blocked ->
                        graph.media.downloadModel(target, blocked) { progress ->
                            runBlocking { setProgress(workDataOf("bytes" to progress.bytes, "total" to progress.total)) }
                        }
                    }
                    graph.transcription.resumeModel(target)
                }
                "refresh" -> if(target.isBlank())graph.podcasts.refreshAll() else graph.podcasts.refresh(target)
                else -> return Result.failure()
            }
            // Show automation observes the resulting download, model and job state (#140).
            Result.success()
        } catch (cancelled: CancellationException) {
            if (kind == "download" || kind == "model")
                graph.jobs.stopped(kind, target, id, if (Build.VERSION.SDK_INT >= 31) stopReason else null)
            throw cancelled
        }
        catch (error: Exception) {
            // The Library already names a feed's HTTP, parse or connection failure: not an app failure (#294).
            val feedReason = if (kind == "refresh") feedRefreshDiagnosticReason(error) else null
            if (feedReason != null) graph.diagnostics.event("worker.failed", reason = feedReason)
            else graph.diagnostics.failure("worker", error)
            if (kind == "download" && error is java.io.IOException && runAttemptCount < 3) Result.retry()
            else {
                // A failure before the transfer starts must not leave the row queued without work.
                if (kind == "download" || kind == "model") graph.jobs.settle(kind, target, "failed", safeFailure(error))
                Result.failure()
            }
        }
    }

    /**
     * Android 12+ refuses the foreground service when the job starts while DistillPod is in the
     * background, and a later attempt would be refused the same way. Like an analysis (#84), the
     * transfer then continues as ordinary work, without the notification (#292), and this returns
     * false. When the process ends first, WorkManager reruns the request and the transfer resumes from
     * its partial file and validators. Other foreground errors propagate.
     */
    private suspend fun startForeground(title: String): Boolean {
        try { setForeground(workForeground(applicationContext, id, title)); return true }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            currentCoroutineContext().ensureActive()
            if (!error.isForegroundStartRejected()) throw error
            return false
        }
    }

    /**
     * Without the foreground service nothing keeps DistillPod's process out of the cached state once
     * the job that runs this worker ends (its time limit, or a reschedule after a process restart), and
     * Android 15+ then blocks the app's network (#292). WorkManager's retry would run in this process
     * while the block lasts and fail at once, so a blocked transfer does not return to WorkManager: its
     * row waits as queued and this run resumes it from the partial file once the network is usable.
     * Meanwhile WorkManager keeps the request's pending job; when Android starts it, the job joins this
     * run and binds the process again, which lifts the block (as does opening the app). A pause still
     * cancels the wait. A failure without a reported block keeps the retry budget below.
     */
    private suspend fun <T> transfer(graph: AppGraph, foreground: Boolean, run: suspend (suspend (java.io.IOException) -> Boolean) -> T): T {
        while (true) {
            try { return run { !foreground && graph.downloadNetwork.blockedAfterFailure() } }
            catch (_: TransferBlocked) {
                Log.i(TAG, "Transfer waits for Android to allow network access")
                graph.downloadNetwork.awaitUsable { graph.settings.flow.first().wifiOnly }
                Log.i(TAG, "Network access allowed; resuming the transfer")
            }
        }
    }

    private companion object { const val TAG = "LibraryWorker" }
}

class UnderstandingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.appGraph
        graph.ready.await()
        val target = inputData.getString("id") ?: return Result.failure()
        when (inputData.getString("kind")) {
            "analysis" -> {
                // A long analysis outlasts an ordinary job's time limit once the user leaves the app (#84).
                // Appended duplicates and finished rows return at once without a notification.
                if (graph.db.library().analysis(target)?.state == "queued") startForeground(graph, "Generating summary & chapters")
                graph.understanding.runAnalysis(target) {
                    if (!isStopped) WorkStop.ENDED
                    else graph.jobs.stopped("analysis:$target", id, if (Build.VERSION.SDK_INT >= 31) stopReason else null)
                }
            }
            "reply" -> graph.understanding.runReply(target)
            "explain" -> graph.explanations.run(target)
            else -> return Result.failure()
        }
        return Result.success()
    }

    /**
     * Without the foreground service the analysis still runs as an ordinary job, and a stop at its time
     * limit resumes from the saved parts, so a refused or failed start never blocks the request.
     */
    private suspend fun startForeground(graph: AppGraph, title: String) {
        try { setForeground(workForeground(applicationContext, id, title)) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            currentCoroutineContext().ensureActive()
            if (!error.isForegroundStartRejected()) graph.diagnostics.failure("worker", error)
        }
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

/** Downloads, transcription and AI work share one channel. Its id keeps existing user settings; recreating it updates the name. */
const val PROCESSING_CHANNEL = "processing"
fun createProcessingChannel(context: Context) = context.getSystemService(NotificationManager::class.java)
    .createNotificationChannel(NotificationChannel(PROCESSING_CHANNEL, "Background tasks", NotificationManager.IMPORTANCE_LOW))

fun workForeground(context: Context, workId: java.util.UUID, title: String, media: Boolean = false): ForegroundInfo {
    val manager = context.getSystemService(NotificationManager::class.java)
    createProcessingChannel(context)
    val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification = NotificationCompat.Builder(context, PROCESSING_CHANNEL).setSmallIcon(android.R.drawable.ic_popup_sync)
        .setContentTitle(title).setContentText("DistillPod · tap to view progress").setContentIntent(intent).setOngoing(true)
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Pause", WorkManager.getInstance(context).createCancelPendingIntent(workId)).build()
    val type = if (media && Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    return ForegroundInfo(workId.hashCode() and Int.MAX_VALUE, notification, type)
}
