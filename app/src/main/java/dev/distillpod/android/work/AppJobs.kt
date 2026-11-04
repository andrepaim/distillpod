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
import dev.distillpod.android.MainActivity
import dev.distillpod.android.appGraph
import dev.distillpod.android.data.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class AppJobs(private val context: Context) {
    private val manager get() = WorkManager.getInstance(context)
    suspend fun download(id: String) {
        val prefs = context.appGraph.settings.flow.first()
        context.appGraph.db.library().episode(id)?.let { ep ->
            if (ep.downloadState == "downloaded" && ep.localFile?.let { java.io.File(it).isFile } == true) return
            context.appGraph.db.library().downloadProgress(id, "queued", ep.downloadedBytes, ep.totalBytes, ep.etag, ep.lastModified, null)
        }
        manager.enqueueUniqueWork("download:$id", ExistingWorkPolicy.KEEP, request("download", id, prefs))
    }
    suspend fun model(id: String) {
        val prefs = context.appGraph.settings.flow.first()
        val old = context.appGraph.db.library().model(id)
        context.appGraph.db.library().putModel(ModelEntity(id, "queued", old?.bytes ?: 0, old?.etag))
        manager.enqueueUniqueWork("model:$id", ExistingWorkPolicy.KEEP, request("model", id, prefs))
    }
    fun refresh() { manager.enqueueUniqueWork("refresh", ExistingWorkPolicy.KEEP, request("refresh", "", Preferences(wifiOnly = false))) }
    suspend fun configureRefresh() {
        val prefs = context.appGraph.settings.flow.first()
        if (prefs.refresh) manager.enqueueUniquePeriodicWork("refresh-periodic", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<LibraryWorker>(6, TimeUnit.HOURS).setInputData(workDataOf("kind" to "refresh"))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()).build())
        else manager.cancelUniqueWork("refresh-periodic")
    }
    fun pauseDownload(id: String) { manager.cancelUniqueWork("download:$id") }
    fun pauseModel(id: String) { manager.cancelUniqueWork("model:$id") }
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
    private fun request(kind: String, id: String, prefs: Preferences) = OneTimeWorkRequestBuilder<LibraryWorker>()
        .setInputData(workDataOf("kind" to kind, "id" to id))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
        .addTag("$kind:$id").build()
}

class LibraryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.appGraph
        graph.ready.await()
        val target = inputData.getString("id") ?: ""
        return try {
            when (inputData.getString("kind")) {
                "download" -> {
                    setForeground(workForeground(applicationContext, id, "Downloading episode"))
                    graph.media.ensureDownloaded(target) { progress ->
                        runBlocking { setProgress(workDataOf("bytes" to progress.bytes, "total" to progress.total)) }
                    }
                }
                "model" -> {
                    setForeground(workForeground(applicationContext, id, "Downloading transcription model"))
                    graph.media.downloadModel(target) { progress ->
                        runBlocking { setProgress(workDataOf("bytes" to progress.bytes, "total" to progress.total)) }
                    }
                    graph.transcription.resumeModel(target)
                }
                "refresh" -> graph.podcasts.refreshAll()
                else -> return Result.failure()
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (inputData.getString("kind") == "download") graph.db.library().episode(target)?.let { ep ->
                    graph.db.library().downloadProgress(target, "paused", ep.downloadedBytes, ep.totalBytes, ep.etag, ep.lastModified, null)
                }
                if (inputData.getString("kind") == "model") graph.db.library().model(target)?.let { graph.db.library().putModel(it.copy(state = "paused", error = null)) }
            }
            throw cancelled
        }
        catch (_: Exception) { Result.failure() }
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
