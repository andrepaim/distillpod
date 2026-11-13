package dev.distillpod.android.validation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import dev.distillpod.android.appGraph
import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.data.PodcastEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Isolated #145 probe, compiled only into dev.distillpod.android.validation.sleep145*.
 * The host sends one short step per broadcast:
 * - `seed` creates an offline synthetic show with two locally generated silent WAV episodes
 *   and disables playback transcription.
 * - `play` (optional `position`) plays the first episode with the second queued next.
 * - `sleep` (`ms`) starts a countdown; `end` sleeps at the end of the episode; `cancel` cancels.
 * - `status` logs player, timer and persisted progress state.
 * The timer is driven through the ordinary playback controller. No network, account, model,
 * recording or owner content is involved.
 */
class SleepTimerProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        check(context.packageName.startsWith("dev.distillpod.android.validation.sleep145"))
        val graph = context.applicationContext.appGraph
        val pending = goAsync()
        graph.scope.launch {
            try {
                withContext(Dispatchers.IO) { graph.ready.await() }
                val action = intent.getStringExtra("action")
                when (action) {
                    "seed" -> withContext(Dispatchers.IO) { seed(context, graph); graph.settings.flag("transcribeOnPlayback", false) }
                    "play" -> { graph.playback.play(FIRST, intent.getLongExtra("position", 0)); graph.playback.enqueue(SECOND) }
                    "sleep" -> graph.playback.startSleepTimer(intent.getLongExtra("ms", 60_000))
                    "end" -> graph.playback.sleepAtEndOfEpisode()
                    "cancel" -> graph.playback.cancelSleepTimer()
                    "status" -> status(graph)
                    else -> Log.w(TAG, "unknown action $action")
                }
                Log.i(TAG, "done $action")
            } catch (error: Exception) { Log.e(TAG, "probe failed", error) }
            finally { pending.finish() }
        }
    }

    private suspend fun status(graph: dev.distillpod.android.AppGraph) {
        val player = graph.playback.sessionPlayer()
        val live = withContext(Dispatchers.Main) {
            "current=${player.currentMediaItem?.mediaId} playing=${player.isPlaying} playWhenReady=${player.playWhenReady} " +
                "position=${player.currentPosition} volume=${player.volume}"
        }
        val timer = graph.playback.sleepTimer.value
        val timerText = timer?.let { "endOfEpisode=${it.endOfEpisode} remaining=${it.remainingAt(SystemClock.elapsedRealtime())} counting=${it.counting}" } ?: "none"
        val saved = withContext(Dispatchers.IO) {
            listOf(FIRST, SECOND).map { id -> id to graph.db.library().episode(id) }
                .joinToString(" ") { (id, ep) -> ep?.let { "$id:position=${it.positionMs},completed=${it.completed}" } ?: "$id:missing" }
        }
        Log.i(TAG, "status $live timer=[$timerText] saved=[$saved]")
    }

    private suspend fun seed(context: Context, graph: dev.distillpod.android.AppGraph) {
        val library = graph.db.library()
        check(library.podcasts().isEmpty()) { "Seed only a fresh validation library" }
        library.putPodcast(PodcastEntity(SHOW, "https://validation.invalid/$SHOW.rss", "Sleep timer validation", author = "Validation"))
        for ((id, ms) in listOf(FIRST to EPISODE_MS, SECOND to EPISODE_MS)) {
            val audio = File(context.filesDir, "media/$id.wav").apply { parentFile!!.mkdirs() }
            writeSilence(audio, ms)
            library.putEpisode(EpisodeEntity(id, SHOW, id, "Silent validation episode $id", "https://validation.invalid/$id.mp3",
                durationMs = ms, localFile = audio.path, downloadState = "downloaded", downloadedBytes = audio.length(),
                totalBytes = audio.length(), mediaFingerprint = "s145-$id"))
        }
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
        const val TAG = "Sleep145"
        const val SHOW = "s145-show"
        const val FIRST = "s145-first"
        const val SECOND = "s145-second"
        const val EPISODE_MS = 600_000L
    }
}
