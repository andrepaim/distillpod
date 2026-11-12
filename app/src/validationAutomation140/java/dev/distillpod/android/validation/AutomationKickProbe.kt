package dev.distillpod.android.validation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.distillpod.android.appGraph
import dev.distillpod.android.data.*
import dev.distillpod.android.work.DOWNLOAD_BLOCKED_MESSAGE
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Isolated #140 probe, compiled only into dev.distillpod.android.validation.automation140*.
 * The host sends one short step per broadcast, so the probe never runs unattended:
 * - `seed` creates an offline synthetic show with three automated jobs: a download Android refused
 *   (#138), local audio whose transcript automation already started, and a download without network.
 * - `chunk` writes one transcription chunk's state and progress the way the transcription worker does.
 * - `tick` writes one playback checkpoint through the call PlaybackService makes each second, without audio.
 * - `dump` logs job states.
 * Each step keeps the broadcast open briefly, as a running worker or playback service keeps the
 * process active. ShowAutomationWorker runs are counted from WorkManager's log. No model, audio
 * decoding, foreground service or network access is involved.
 */
class AutomationKickProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        check(context.packageName.startsWith("dev.distillpod.android.validation.automation140"))
        val graph = context.applicationContext.appGraph
        val pending = goAsync()
        graph.scope.launch {
            try {
                graph.ready.await()
                when (val action = intent.getStringExtra("action")) {
                    "seed" -> seed(context, graph.db)
                    "chunk" -> chunk(graph.db, intent.getIntExtra("index", 1), intent.getBooleanExtra("last", false))
                    "tick" -> tick(graph, intent.getIntExtra("second", 0))
                    "dump" -> dump(graph.db)
                    else -> Log.w(TAG, "unknown action $action")
                }
            } catch (error: Exception) { Log.e(TAG, "probe failed", error) }
            finally { pending.finish() }
        }
    }

    private suspend fun seed(context: Context, db: AppDatabase) {
        val library = db.library()
        check(library.podcasts().isEmpty()) { "Seed only a fresh validation library" }
        val audio = File(context.filesDir, "media/$E1.audio").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(4096)) }
        library.putPodcast(PodcastEntity(SHOW, "https://validation.invalid/automation140.rss", "Automation validation"))
        library.putEpisode(EpisodeEntity(PLAYED, SHOW, "played", "Played episode", "https://validation.invalid/played.mp3", durationMs = 600_000))
        library.putEpisode(EpisodeEntity(E2, SHOW, "refused", "Refused download", "https://validation.invalid/refused.mp3",
            downloadState = "paused", error = DOWNLOAD_BLOCKED_MESSAGE, mediaFingerprint = "v140-refused"))
        library.putEpisode(EpisodeEntity(E1, SHOW, "local", "Local audio", "https://validation.invalid/local.mp3", durationMs = CHUNK_MS * 12,
            localFile = audio.path, downloadState = "downloaded", downloadedBytes = audio.length(), totalBytes = audio.length(), mediaFingerprint = "v140-local"))
        library.putTranscript(TranscriptEntity(E1, "v140-revision", "v140-local", "tiny", "en", state = "processing", durationMs = CHUNK_MS * 12))
        library.putEpisode(EpisodeEntity(E3, SHOW, "offline", "Offline download", "https://validation.invalid/offline.mp3", mediaFingerprint = "v140-offline"))
        db.automation().putShow(ShowAutomationEntity(SHOW, System.currentTimeMillis(), initialized = true))
        db.automation().insertJob(EpisodeAutomationEntity(E2, SHOW))
        // Automation already started this transcript; the probe stands in for its worker.
        db.automation().insertJob(EpisodeAutomationEntity(E1, SHOW, "transcribing"))
        db.automation().insertJob(EpisodeAutomationEntity(E3, SHOW))
        Log.i(TAG, "seeded")
        dump(db)
    }

    private suspend fun chunk(db: AppDatabase, index: Int, last: Boolean) {
        val library = db.library()
        val row = library.transcript(E1) ?: return
        library.putTranscript(row.copy(state = "processing"))
        delay(6_000)
        library.putTranscript(row.copy(state = if (last) "ready" else "queued", processedMs = CHUNK_MS * index))
        Log.i(TAG, "chunk $index${if (last) " ready" else ""}")
        delay(2_000)
        if (last) dump(db)
    }

    private suspend fun tick(graph: dev.distillpod.android.AppGraph, second: Int) {
        graph.portableSync.checkpoint(PLAYED, second * 1_000L, 600_000, false, System.currentTimeMillis(), SESSION,
            second * 1_000L, active = true)
        delay(900)
    }

    private suspend fun dump(db: AppDatabase) {
        val library = db.library()
        for (job in db.automation().jobs()) {
            val ep = library.episode(job.episodeId)
            Log.i(TAG, "job ${job.episodeId} stage=${job.stage} download=${ep?.downloadState} transcript=${library.transcript(job.episodeId)?.let { "${it.state}:${it.processedMs}" }} analysis=${library.analysis(job.episodeId)?.state}")
        }
    }

    private companion object {
        const val TAG = "Automation140"
        const val SHOW = "v140-show"
        const val PLAYED = "v140-played"
        const val E1 = "v140-local"
        const val E2 = "v140-refused"
        const val E3 = "v140-offline"
        const val CHUNK_MS = 120_000L
        val SESSION: String = UUID.randomUUID().toString()
    }
}
