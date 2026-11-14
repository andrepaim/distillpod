package dev.distillpod.android.features

import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.playback.PlaybackState
import kotlin.math.abs

/**
 * Queue edits for drag and Undo, expressed with the existing by-identity actions: `moveQueue(id, ±1)` and
 * `enqueue(id, next)`. Nothing here holds an index across an edit; each step resolves [id] again (#203).
 */

/** The list with the element at [from] moved to [to]. */
internal fun <T> List<T>.moved(from: Int, to: Int): List<T> =
    if (from == to || from !in indices || to !in indices) this else toMutableList().apply { add(to, removeAt(from)) }

/**
 * Single-step offsets that move [id] to [target] in [queue]. Empty when the episode is absent or already
 * there; the target is clamped to the playlist.
 */
internal fun queueMoveSteps(queue: List<String>, id: String, target: Int): List<Int> {
    val from = queue.indexOf(id)
    if (from < 0) return emptyList()
    return steps(target.coerceIn(0, queue.lastIndex) - from)
}

/** How Undo re-inserts a removed episode: enqueue it next or last, then move it by single steps. */
internal data class QueueRestore(val next: Boolean, val steps: List<Int>)

/**
 * Re-inserting [id] at [index], its position before removal. [queue] is the playlist now, with or without
 * [id] (a removal may still be pending); [currentId] is the playing episode. "Play next" inserts after the
 * current episode and "Add to queue" appends, so whichever lands closer needs fewer moves.
 */
internal fun queueRestorePlan(queue: List<String>, currentId: String?, id: String, index: Int): QueueRestore {
    val remaining = queue.filter { it != id }
    val target = index.coerceIn(0, remaining.size)
    val appended = remaining.size
    val afterCurrent = currentId?.let { remaining.indexOf(it) }?.takeIf { it >= 0 }?.plus(1)
    return if (afterCurrent != null && abs(target - afterCurrent) < abs(target - appended)) QueueRestore(true, steps(target - afterCurrent))
    else QueueRestore(false, steps(target - appended))
}

private fun steps(delta: Int): List<Int> = List(abs(delta)) { if (delta > 0) 1 else -1 }

/** Listening time left in [episodes]; finished ones, which read "Played", add nothing. */
internal fun queueRemainingMs(episodes: List<EpisodeEntity>, player: PlaybackState): Long = episodes.sumOf { ep ->
    if (queueTimeLabel(ep, player) == "Played") 0L
    else (listeningDuration(ep, player) - listeningPosition(ep, player)).coerceAtLeast(0)
}

/** "Up next · 3 · 1 hr 20 min"; the time is left out when no duration is known. */
internal fun upNextHeading(count: Int, remainingMs: Long): String = if (count == 0) "Up next" else
    listOf("Up next", count.toString(), episodeLengthLabel(remainingMs)).filter(String::isNotBlank).joinToString(" · ")
