package dev.distillpod.android.validation

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import dev.distillpod.android.appGraph
import dev.distillpod.android.data.CoverageEntity
import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.android.data.TranscriptEntity
import dev.distillpod.android.widget.NowPlayingWidget
import dev.distillpod.android.widget.NowPlayingWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Isolated #189 probe, compiled only into dev.distillpod.android.validation.widget189*.
 * - `seed` creates an offline synthetic show with one silent local episode whose complete
 *   publisher transcript keeps capture local (no AI, transcription or network request).
 * - `play` (optional `position`) plays it through the ordinary playback controller.
 * - `status` logs the widget's live state, placed widget count and saved moments.
 */
class WidgetProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        check(context.packageName.startsWith("dev.distillpod.android.validation.widget189"))
        val graph = context.applicationContext.appGraph
        val pending = goAsync()
        graph.scope.launch {
            try {
                withContext(Dispatchers.IO) { graph.ready.await() }
                val action = intent.getStringExtra("action")
                when (action) {
                    "seed" -> withContext(Dispatchers.IO) {
                        graph.settings.flag("transcribeOnPlayback", false)
                        graph.settings.flag("distillSound", false)
                        seed(context, graph)
                    }
                    "play" -> graph.playback.play(EPISODE, intent.getLongExtra("position", 0))
                    "status" -> {
                        val placed = AppWidgetManager.getInstance(context)
                            .getAppWidgetIds(ComponentName(context, NowPlayingWidgetProvider::class.java)).size
                        val moments = withContext(Dispatchers.IO) { graph.db.library().distillations().filter { it.episodeId == EPISODE } }
                        Log.i(TAG, "status placed=$placed live=${NowPlayingWidget.live} moments=${moments.map { "${it.startMs}-${it.endMs}" }}")
                    }
                    else -> Log.w(TAG, "unknown action $action")
                }
                Log.i(TAG, "done $action")
            } catch (error: Exception) { Log.e(TAG, "probe failed", error) }
            finally { pending.finish() }
        }
    }

    private suspend fun seed(context: Context, graph: dev.distillpod.android.AppGraph) {
        val library = graph.db.library()
        check(library.podcasts().isEmpty()) { "Seed only a fresh validation library" }
        library.putPodcast(PodcastEntity(SHOW, "https://validation.invalid/$SHOW.rss", "Widget validation show", author = "Validation"))
        val audio = File(context.filesDir, "media/$EPISODE.wav").apply { parentFile!!.mkdirs() }
        writeSilence(audio, EPISODE_MS)
        library.putEpisode(EpisodeEntity(EPISODE, SHOW, EPISODE, "Silent widget validation episode", "https://validation.invalid/$EPISODE.mp3",
            durationMs = EPISODE_MS, localFile = audio.path, downloadState = "downloaded", downloadedBytes = audio.length(),
            totalBytes = audio.length(), mediaFingerprint = FINGERPRINT))
        library.putTranscript(TranscriptEntity(EPISODE, FINGERPRINT, FINGERPRINT, "publisher", "en", source = "publisher",
            state = "ready", durationMs = EPISODE_MS, processedMs = EPISODE_MS))
        library.putCoverage(CoverageEntity(EPISODE, FINGERPRINT, 0, EPISODE_MS))
        Log.i(TAG, "seeded")
    }

    private fun writeSilence(file: File, durationMs: Long) {
        val rate = 8_000
        val data = rate * 2L * durationMs / 1000
        RandomAccessFile(file, "rw").use { out ->
            out.setLength(0)
            val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt((36 + data).toInt()).put("WAVE".toByteArray())
                .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(data.toInt())
            out.write(header.array())
            out.setLength(44 + data) // Sparse zero samples: complete silence.
        }
    }

    private companion object {
        const val TAG = "Widget189"
        const val SHOW = "w189-show"
        const val EPISODE = "w189-episode"
        const val FINGERPRINT = "w189-silence"
        const val EPISODE_MS = 600_000L
    }
}

/** Asks the launcher to place the validation widget; the person (or host tap) confirms the system dialog. */
class PinWidgetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(packageName.startsWith("dev.distillpod.android.validation.widget189"))
        val manager = getSystemService(AppWidgetManager::class.java)
        Log.i("Widget189", "pin supported=${manager.isRequestPinAppWidgetSupported} requested=${
            manager.requestPinAppWidget(ComponentName(this, NowPlayingWidgetProvider::class.java), null, null)}")
        finish()
    }
}
