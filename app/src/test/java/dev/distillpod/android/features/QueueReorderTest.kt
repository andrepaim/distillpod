package dev.distillpod.android.features

import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.playback.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Drag and Undo for the queue, replayed through the single-step by-identity moves the ViewModel offers. */
class QueueReorderTest {
    /** The playback layer's move: resolve [id] now, then swap with its neighbour if one exists. */
    private fun MutableList<String>.move(id: String, offset: Int) {
        val at = indexOf(id); val to = at + offset
        if (at >= 0 && to in indices) add(to, removeAt(at))
    }
    /** `enqueueMediaItem`: next goes after the current episode, otherwise last. */
    private fun MutableList<String>.enqueue(id: String, next: Boolean, current: String?) {
        remove(id)
        val at = current?.let(::indexOf)?.takeIf { it >= 0 }
        if (next && at != null) add(at + 1, id) else add(id)
    }

    @Test fun `moved keeps every element and ignores out of range indices`() {
        assertEquals(listOf("b", "c", "a"), listOf("a", "b", "c").moved(0, 2))
        assertEquals(listOf("c", "a", "b"), listOf("a", "b", "c").moved(2, 0))
        assertEquals(listOf("a", "b"), listOf("a", "b").moved(0, 5))
    }

    @Test fun `drag steps reach the dropped position by identity`() {
        val queue = listOf("h", "now", "a", "b", "c", "d")
        assertEquals(listOf(1, 1, 1), queueMoveSteps(queue, "a", 5))
        assertEquals(listOf(-1, -1), queueMoveSteps(queue, "d", 3))
        assertEquals(emptyList<Int>(), queueMoveSteps(queue, "b", 3))
        assertEquals(emptyList<Int>(), queueMoveSteps(queue, "missing", 0))
        // A target past the end is clamped rather than moving a different episode.
        assertEquals(listOf(1), queueMoveSteps(queue, "c", 99))
        val replayed = queue.toMutableList().apply { queueMoveSteps(queue, "a", 4).forEach { move("a", it) } }
        assertEquals(listOf("h", "now", "b", "c", "a", "d"), replayed)
    }

    @Test fun `steps only ever move the dragged episode, even if another edit lands first`() {
        val queue = mutableListOf("now", "a", "b", "c")
        val steps = queueMoveSteps(queue, "c", 1)
        // An external edit moves "a" to the end before the drag's moves run.
        queue.move("a", 1); queue.move("a", 1)
        steps.forEach { queue.move("c", it) }
        // "c" moved by its own identity; every other episode keeps its relative order.
        assertEquals(listOf("now", "b", "a"), queue.filter { it != "c" })
        assertEquals(listOf(-1, -1), steps)
    }

    @Test fun `undo re-inserts at the original index for every position`() {
        val original = listOf("h1", "h2", "now", "a", "b", "c", "d")
        original.forEachIndexed { index, id ->
            if (id == "now") return@forEachIndexed
            val queue = original.toMutableList().apply { remove(id) }
            val plan = queueRestorePlan(queue, "now", id, index)
            queue.enqueue(id, plan.next, "now")
            plan.steps.forEach { queue.move(id, it) }
            assertEquals("restoring $id", original, queue)
        }
    }

    @Test fun `undo picks the cheaper insertion and plans before the removal has landed`() {
        assertEquals(QueueRestore(true, emptyList()), queueRestorePlan(listOf("now", "b", "c", "d", "e"), "now", "a", 1))
        assertEquals(QueueRestore(false, emptyList()), queueRestorePlan(listOf("now", "a", "b", "c"), "now", "d", 4))
        // The removed episode may still be listed; the plan is for the playlist without it.
        assertEquals(QueueRestore(false, listOf(-1)), queueRestorePlan(listOf("now", "a", "b", "c", "d"), "now", "c", 3))
        // Without a current episode only appending is possible; a stale index is clamped.
        assertEquals(QueueRestore(false, listOf(-1, -1)), queueRestorePlan(listOf("a", "b"), null, "x", 0))
        assertEquals(QueueRestore(false, emptyList()), queueRestorePlan(listOf("now", "a"), "now", "x", 40))
    }

    @Test fun `up next heading counts episodes and remaining time`() {
        val player = PlaybackState(episodeId = "a", positionMs = 600_000, durationMs = 1_800_000)
        val episodes = listOf(
            EpisodeEntity("a", "s", "a", "A", "u", durationMs = 1_700_000),
            EpisodeEntity("b", "s", "b", "B", "u", durationMs = 3_600_000, positionMs = 1_200_000),
            EpisodeEntity("c", "s", "c", "C", "u", durationMs = 900_000, positionMs = 900_000, completed = true))
        // The current item uses the player's duration and position; finished items add nothing.
        assertEquals(1_200_000L + 2_400_000L, queueRemainingMs(episodes, player))
        assertEquals("Up next · 2 · 1 hr 0 min", upNextHeading(2, 3_600_000))
        assertEquals("Up next · 1", upNextHeading(1, 0))
        assertEquals("Up next", upNextHeading(0, 0))
    }
}
