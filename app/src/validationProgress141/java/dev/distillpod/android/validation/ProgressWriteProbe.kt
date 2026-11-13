package dev.distillpod.android.validation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import dev.distillpod.android.appGraph
import dev.distillpod.android.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

/**
 * Isolated #141 probe, compiled only into dev.distillpod.android.validation.progress141*.
 * The host sends one short step per broadcast:
 * - `seed` creates an offline synthetic library (default 30 subscribed shows x 100 episodes) whose
 *   newest episode has a locally generated silent WAV, and disables playback transcription.
 * - `play` / `pause` drive the ordinary playback controller.
 * - `mark` starts a measurement window; `stats` logs episode/player_session row writes (SQLite
 *   triggers) and Room invalidations of those tables since `mark`, with per-minute rates.
 * No network, account, model, recording or owner content is involved.
 */
class ProgressWriteProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        check(context.packageName.startsWith("dev.distillpod.android.validation.progress141"))
        val graph = context.applicationContext.appGraph
        val pending = goAsync()
        graph.scope.launch {
            try {
                withContext(Dispatchers.IO) { graph.ready.await(); install(graph.db) }
                when (val action = intent.getStringExtra("action")) {
                    "seed" -> withContext(Dispatchers.IO) {
                        seed(context, graph, intent.getIntExtra("shows", 30), intent.getIntExtra("episodes", 100))
                        graph.settings.flag("transcribeOnPlayback", false)
                    }
                    "play" -> graph.playback.play(AUDIO, intent.getLongExtra("position", 0))
                    "pause" -> graph.playback.pause()
                    "mark" -> withContext(Dispatchers.IO) { mark(graph.db) }
                    "stats" -> withContext(Dispatchers.IO) { stats(graph.db) }
                    else -> Log.w(TAG, "unknown action $action")
                }
            } catch (error: Exception) { Log.e(TAG, "probe failed", error) }
            finally { pending.finish() }
        }
    }

    private fun install(db: AppDatabase) {
        if (observer != null) return
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE IF NOT EXISTS probe141_counts(name TEXT PRIMARY KEY NOT NULL, n INTEGER NOT NULL)")
        for (table in listOf("episodes", "player_session")) {
            sql.execSQL("INSERT OR IGNORE INTO probe141_counts(name, n) VALUES('$table', 0)")
            for (op in listOf("INSERT", "UPDATE", "DELETE")) sql.execSQL(
                "CREATE TRIGGER IF NOT EXISTS probe141_${table}_$op AFTER $op ON $table BEGIN " +
                    "UPDATE probe141_counts SET n = n + 1 WHERE name = '$table'; END")
        }
        val tracker = object : InvalidationTracker.Observer(arrayOf("episodes", "player_session")) {
            override fun onInvalidated(tables: Set<String>) {
                if ("episodes" in tables) episodeInvalidations.incrementAndGet()
                if ("player_session" in tables) sessionInvalidations.incrementAndGet()
            }
        }
        db.invalidationTracker.addObserver(tracker)
        observer = tracker
    }

    private suspend fun seed(context: Context, graph: dev.distillpod.android.AppGraph, shows: Int, perShow: Int) {
        val library = graph.db.library()
        check(library.podcasts().isEmpty()) { "Seed only a fresh validation library" }
        val audio = File(context.filesDir, "media/$AUDIO.wav").apply { parentFile!!.mkdirs() }
        writeSilence(audio, AUDIO_MS)
        val now = System.currentTimeMillis()
        val body = "Synthetic validation episode description. ".repeat(10)
        graph.db.withTransaction {
            for (s in 0 until shows) {
                val show = "p141-show-%02d".format(s)
                library.putPodcast(PodcastEntity(show, "https://validation.invalid/$show.rss", "Validation show %02d".format(s),
                    author = "Validation", description = body))
                library.putEpisodes((0 until perShow).map { e ->
                    val id = if (s == 0 && e == 0) AUDIO else "$show-ep-%03d".format(e)
                    EpisodeEntity(id, show, id, "Validation episode %02d-%03d".format(s, e), "https://validation.invalid/$id.mp3",
                        description = body, publishedAt = now - (e * shows + s) * 3_600_000L, durationMs = 1_800_000,
                        positionMs = if (e % 7 == 3) 600_000 else 0, lastPlayedAt = if (e % 7 == 3) now - e * 60_000L else 0,
                        localFile = if (id == AUDIO) audio.path else null, downloadState = if (id == AUDIO) "downloaded" else "none",
                        downloadedBytes = if (id == AUDIO) audio.length() else 0, totalBytes = if (id == AUDIO) audio.length() else 0,
                        mediaFingerprint = "p141-$id")
                })
            }
        }
        Log.i(TAG, "seeded shows=$shows episodes=${library.episodes().size}")
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

    private fun counts(db: AppDatabase): Map<String, Long> =
        db.openHelper.readableDatabase.query("SELECT name, n FROM probe141_counts").use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) }
        }

    private fun mark(db: AppDatabase) {
        val c = counts(db)
        start = Window(SystemClock.elapsedRealtime(), c["episodes"] ?: 0, c["player_session"] ?: 0,
            episodeInvalidations.get(), sessionInvalidations.get())
        Log.i(TAG, "mark")
    }

    private fun stats(db: AppDatabase) {
        val from = start ?: run { Log.w(TAG, "stats before mark"); return }
        val c = counts(db)
        val minutes = (SystemClock.elapsedRealtime() - from.at) / 60_000.0
        fun rate(n: Long) = "%.1f".format(n / minutes)
        val episodeWrites = (c["episodes"] ?: 0) - from.episodeWrites
        val sessionWrites = (c["player_session"] ?: 0) - from.sessionWrites
        val episodeInv = episodeInvalidations.get() - from.episodeInvalidations
        val sessionInv = sessionInvalidations.get() - from.sessionInvalidations
        Log.i(TAG, "stats minutes=${"%.2f".format(minutes)} episodeWrites=$episodeWrites (${rate(episodeWrites)}/min) " +
            "episodeInvalidations=$episodeInv (${rate(episodeInv)}/min) sessionWrites=$sessionWrites (${rate(sessionWrites)}/min) " +
            "sessionInvalidations=$sessionInv (${rate(sessionInv)}/min)")
    }

    private data class Window(val at: Long, val episodeWrites: Long, val sessionWrites: Long,
        val episodeInvalidations: Long, val sessionInvalidations: Long)

    private companion object {
        const val TAG = "Progress141"
        const val AUDIO = "p141-audio"
        const val AUDIO_MS = 1_800_000L
        val episodeInvalidations = AtomicLong()
        val sessionInvalidations = AtomicLong()
        @Volatile var observer: InvalidationTracker.Observer? = null
        @Volatile var start: Window? = null
    }
}
