package dev.distillpod.android.validation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.WorkManager
import dev.distillpod.android.appGraph
import dev.distillpod.android.data.*
import dev.distillpod.android.work.DOWNLOAD_BLOCKED_MESSAGE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Isolated #292 probe, compiled only into dev.distillpod.android.validation.download292.
 * The host sends one short step per broadcast while the app has no visible activity, so each
 * download job starts in the background, where Android refuses its foreground service:
 * - `seed` adds one episode with the given public audio URL; feed refresh stays off.
 * - `download` and `pause` call the app's own Download and Pause.
 * - `legacy` leaves the row paused the way an earlier version did after refused starts (#138).
 * - `delete` removes the downloaded copy so the next scenario starts from zero.
 * - `dump` logs the row, the partial file and the WorkManager request.
 */
class BackgroundDownloadProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        check(context.packageName == "dev.distillpod.android.validation.download292")
        val graph = context.applicationContext.appGraph
        val pending = goAsync()
        graph.scope.launch {
            try {
                graph.ready.await()
                val dao = graph.db.library()
                when (val action = intent.getStringExtra("action")) {
                    "seed" -> {
                        graph.settings.flag("refresh", false)
                        if (dao.podcast(SHOW) == null) dao.putPodcast(PodcastEntity(SHOW, "https://validation.invalid/download292.rss", "Download validation"))
                        dao.putEpisode(EpisodeEntity(EPISODE, SHOW, EPISODE, "Background download", intent.getStringExtra("url")!!))
                    }
                    "download" -> graph.jobs.download(EPISODE)
                    "pause" -> graph.jobs.pauseDownload(EPISODE)
                    "legacy" -> dao.episode(EPISODE)?.let { dao.putEpisode(it.copy(downloadState = "paused", error = DOWNLOAD_BLOCKED_MESSAGE)) }
                    "delete" -> graph.media.deleteDownload(EPISODE)
                    "dump" -> {}
                    else -> Log.w(TAG, "unknown action $action")
                }
                dump(context, dao)
            } catch (error: Exception) { Log.e(TAG, "probe failed: ${error.javaClass.name}") }
            finally { pending.finish() }
        }
    }

    private suspend fun dump(context: Context, dao: LibraryDao) = withContext(Dispatchers.IO) {
        val row = dao.episode(EPISODE)
        val partial = File(context.filesDir, "media/$EPISODE.partial")
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork("download:$EPISODE").get()
            .joinToString { "${it.state}/attempt=${it.runAttemptCount}/stop=${it.stopReason}" }
        Log.i(TAG, "row=${row?.downloadState} bytes=${row?.downloadedBytes} total=${row?.totalBytes} error=${row?.error} " +
            "partial=${partial.takeIf { it.isFile }?.length()} file=${row?.localFile?.let(::File)?.takeIf { it.isFile }?.length()} work=[$work]")
    }

    private companion object {
        const val TAG = "Download292"
        const val SHOW = "v292-show"
        const val EPISODE = "v292-episode"
    }
}
