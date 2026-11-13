package dev.distillpod.android.validation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.distillpod.android.appGraph
import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.data.PodcastEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Isolated #183 probe, compiled only into dev.distillpod.android.validation.effects183*.
 * The host sends one short step per broadcast:
 * - `seed` creates two offline synthetic shows, each with one locally generated silent WAV episode,
 *   and disables playback transcription.
 * - `play` (`episode` 1 or 2) plays that episode.
 * - `flag` (`key` skipSilence|volumeBoost, `value`) changes an effect setting.
 * - `speed` (`value`) chooses a speed; `keep` (`value` true|false) keeps it for the current show.
 * - `sleep` (`ms`) starts a sleep timer; `pause` pauses.
 * - `status` logs player, effect, timer and stored speed state.
 * No network, account, model, recording or owner content is involved.
 */
class PlaybackEffectsProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        check(context.packageName.startsWith("dev.distillpod.android.validation.effects183"))
        val graph = context.applicationContext.appGraph
        val pending = goAsync()
        graph.scope.launch {
            try {
                withContext(Dispatchers.IO) { graph.ready.await() }
                val action = intent.getStringExtra("action")
                when (action) {
                    "seed" -> withContext(Dispatchers.IO) { seed(context, graph); graph.settings.flag("transcribeOnPlayback", false) }
                    "play" -> graph.playback.play(if (intent.getStringExtra("episode") == "2") SECOND else FIRST, 0)
                    "flag" -> graph.settings.flag(checkNotNull(intent.getStringExtra("key")).also { require(it in setOf("skipSilence", "volumeBoost")) },
                        intent.getStringExtra("value").toBoolean())
                    "speed" -> graph.playback.speed(checkNotNull(intent.getStringExtra("value")).toFloat())
                    "keep" -> {
                        val current = graph.playback.state.value
                        val show = withContext(Dispatchers.IO) { graph.db.library().episode(checkNotNull(current.episodeId))!!.podcastId }
                        val keep = intent.getStringExtra("value").toBoolean()
                        graph.settings.updateSpeeds { if (keep) it.keep(show, current.speed) else it.forget(show) }
                    }
                    "sleep" -> graph.playback.startSleepTimer(checkNotNull(intent.getStringExtra("ms")).toLong())
                    "pause" -> graph.playback.pause()
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
            "current=${player.currentMediaItem?.mediaId} playing=${player.isPlaying} position=${player.currentPosition} " +
                "volume=${player.volume} speed=${player.playbackParameters.speed} elapsed=${android.os.SystemClock.elapsedRealtime()}"
        }
        val prefs = graph.settings.flow.first()
        Log.i(TAG, "status $live skipSilence=${prefs.skipSilence} volumeBoost=${prefs.volumeBoost} " +
            "boost=${graph.playbackEvents.volumeBoost.value} timer=${graph.playback.sleepTimer.value} speeds=${graph.settings.speeds.first()}")
    }

    private suspend fun seed(context: Context, graph: dev.distillpod.android.AppGraph) {
        val library = graph.db.library()
        check(library.podcasts().isEmpty()) { "Seed only a fresh validation library" }
        for ((show, id) in listOf(SHOW_A to FIRST, SHOW_B to SECOND)) {
            library.putPodcast(PodcastEntity(show, "https://validation.invalid/$show.rss", "Effects validation $show", author = "Validation"))
            val audio = File(context.filesDir, "media/$id.wav").apply { parentFile!!.mkdirs() }
            writeSilence(audio, EPISODE_MS)
            library.putEpisode(EpisodeEntity(id, show, id, "Silent validation episode $id", "https://validation.invalid/$id.mp3",
                durationMs = EPISODE_MS, localFile = audio.path, downloadState = "downloaded", downloadedBytes = audio.length(),
                totalBytes = audio.length(), mediaFingerprint = "e183-$id"))
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
        const val TAG = "Effects183"
        const val SHOW_A = "e183-show-a"
        const val SHOW_B = "e183-show-b"
        const val FIRST = "e183-first"
        const val SECOND = "e183-second"
        const val EPISODE_MS = 600_000L
    }
}
