package dev.distillpod.android.playback

import androidx.media3.common.Player
import androidx.room.withTransaction
import dev.distillpod.android.data.AppDatabase
import dev.distillpod.android.data.PlayerSessionEntity
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Interval between local progress checkpoints during continuous playback. Each checkpoint
 * invalidates the episodes table, so every library observer re-reads; the live position shown
 * in the UI comes from the player instead (#141).
 */
internal const val PROGRESS_CHECKPOINT_INTERVAL_MS = 10_000L

/** One persisted view of the service player, sent to the single playback writer. */
internal data class PlaybackSnapshot(val ids: List<String>, val id: String?, val position: Long, val duration: Long,
    val complete: Boolean, val speed: Float, val at: Long = System.currentTimeMillis(), val boundary: Boolean = false,
    val playing: Boolean = false, val queueBoundary: Boolean = false, val queueRestored: Boolean = false,
    val suppressQueue: Boolean = false, val elapsed: Long = android.os.SystemClock.elapsedRealtime())

/**
 * Decides when a snapshot's progress reaches Room. Boundaries (play/pause, seek, item departure,
 * stop, completion) are written exactly; continuous playback is written for a new item and then at
 * most once per [intervalMs]. A failed write is retried by the next snapshot.
 */
internal class ProgressCheckpoints(private val intervalMs: Long = PROGRESS_CHECKPOINT_INTERVAL_MS) {
    private var lastId: String? = null
    private var lastElapsed = 0L
    private var pendingBoundary = false

    /** Null when no write is due; otherwise whether to write it as a forced boundary. */
    fun due(id: String, playing: Boolean, boundary: Boolean, elapsed: Long): Boolean? = when {
        boundary || pendingBoundary -> true
        playing && (id != lastId || elapsed - lastElapsed >= intervalMs) -> false
        else -> null
    }
    fun written(id: String, elapsed: Long) { lastId = id; lastElapsed = elapsed; pendingBoundary = false }
    fun failed(boundary: Boolean) { if (boundary) pendingBoundary = true else lastId = null }
}

/**
 * Leaving the current item for a different one (manual next/previous, selection, removal or a
 * skipped item) checkpoints the departed item at its last position. Automatic completion is
 * recorded separately as a finished item.
 */
internal fun shouldCheckpointDeparture(reason: Int, oldId: String?, newId: String?) =
    oldId != null && oldId != newId && reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION

/** Persists playback snapshots: bounded progress checkpoints, local queue order and changed session settings. */
internal class PlaybackSnapshotWriter(private val db: AppDatabase,
    private val checkpoint: suspend (id: String, value: PlaybackSnapshot) -> Unit,
    private val persistQueue: suspend (List<String>) -> Unit,
    private val importedQueue: MutableStateFlow<List<String>?>,
    private val checkpoints: ProgressCheckpoints = ProgressCheckpoints()) {
    /** Queue order last observed as persisted; explicit cloud choices also set it. */
    var lastQueue: List<String>? = null

    suspend fun write(value: PlaybackSnapshot) {
        val dao = db.library()
        var attempted: Boolean? = null
        try {
            val ep = value.id?.let { dao.episode(it) }
            // Diagnostics may play a temporary clip; it must not replace the podcast queue.
            if (value.id != null && ep == null) return
            val progress = ep?.let { checkpoints.due(it.id, value.playing, value.boundary, value.elapsed)?.let { force -> it.id to force } }
            attempted = progress?.second
            db.withTransaction {
                if (progress != null) checkpoint(progress.first, value.copy(boundary = progress.second))
                if (value.ids.isNotEmpty() || value.id == null) {
                    val known = value.ids.filter { dao.episode(it) != null }.distinct()
                    if (!value.suppressQueue) {
                        val imported = importedQueue.value == known
                        if (imported) importedQueue.value = null
                        val decision = queuePersistenceDecision(lastQueue, known, dao.queue().map { it.episodeId },
                            imported, value.queueBoundary, value.queueRestored)
                        if (decision.write) persistQueue(known)
                        lastQueue = decision.baseline
                    }
                    // Reading is cheap; an unchanged upsert would still invalidate observers each second.
                    val session = PlayerSessionEntity(episodeId = value.id, speed = value.speed)
                    if (dao.playerSession() != session) dao.putPlayerSession(session)
                }
            }
            if (progress != null) checkpoints.written(progress.first, value.elapsed)
        } catch (_: Exception) {
            // A later snapshot recovers a transient write failure.
            attempted?.let(checkpoints::failed)
        }
    }
}
