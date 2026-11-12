package dev.distillpod.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.features.QueueContent
import dev.distillpod.android.features.listeningDuration
import dev.distillpod.android.features.queueTimeLabel
import dev.distillpod.android.playback.PlaybackState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val MIN = 60_000L

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueueLabelsTest {
    @get:Rule val compose = createComposeRule()
    private fun episode(id: String, durationMs: Long = 30 * MIN, positionMs: Long = 0, completed: Boolean = false) =
        EpisodeEntity(id, "show", id, "Episode $id", "https://example.test/$id",
            durationMs = durationMs, positionMs = positionMs, completed = completed)

    @Test fun `current item uses the player duration and others keep the stored duration`() {
        val declared = episode("a", durationMs = 36 * MIN)
        val playing = PlaybackState(episodeId = "a", positionMs = 0, durationMs = 40 * MIN, playing = true, queue = listOf("a"))
        assertEquals(40 * MIN, listeningDuration(declared, playing))
        assertEquals("40 min left", queueTimeLabel(declared, playing))
        // Until the player reports a duration, the stored value remains the best estimate.
        assertEquals("36 min left", queueTimeLabel(declared, playing.copy(durationMs = 0)))
        assertEquals("36 min left", queueTimeLabel(declared, playing.copy(episodeId = "other")))
    }

    @Test fun `completed items read Played instead of minutes left`() {
        val finished = episode("a", durationMs = 30 * MIN, positionMs = 29 * MIN + 30_000, completed = true)
        assertEquals("Played", queueTimeLabel(finished, PlaybackState(episodeId = "b", queue = listOf("a", "b"))))
        assertEquals("Played", queueTimeLabel(finished.copy(positionMs = 30 * MIN), PlaybackState()))
        // The current item at its end is also played; a replay shows its remaining time again.
        val ended = PlaybackState(episodeId = "a", positionMs = 30 * MIN, durationMs = 30 * MIN, queue = listOf("a"))
        assertEquals("Played", queueTimeLabel(finished, ended))
        assertEquals("25 min left", queueTimeLabel(finished, ended.copy(positionMs = 5 * MIN, playing = true)))
        assertEquals("1 min left", queueTimeLabel(finished.copy(completed = false), PlaybackState()))
    }

    @Test fun `queue rows show player time for current and Played for finished history`() {
        val episodes = listOf(episode("a", positionMs = 29 * MIN + 30_000, completed = true), episode("b", durationMs = 36 * MIN), episode("c", durationMs = 50 * MIN))
        compose.setContent { QueueContent(episodes, emptyList(),
            PlaybackState(episodeId = "b", positionMs = 0, durationMs = 40 * MIN, playing = true, queue = listOf("a", "b", "c")),
            {}, {}, {}, {}, { _, _ -> }, {}) }
        compose.onNodeWithTag("queue-list").performScrollToNode(hasText("40 min left"))
        compose.onNodeWithText("40 min left").assertIsDisplayed()
        compose.onAllNodesWithText("36 min left").assertCountEquals(0)
        compose.onNodeWithTag("queue-list").performScrollToNode(hasText("50 min left"))
        compose.onNodeWithTag("queue-list").performScrollToNode(hasText("Played"))
        compose.onNodeWithText("Played").assertIsDisplayed()
        compose.onAllNodesWithText("1 min left").assertCountEquals(0)
    }

    @Test fun `queue opens on the current item after a long history and says nothing is up next`() {
        val ids = listOf("a", "b", "c", "d", "e", "f")
        val episodes = ids.map { episode(it, positionMs = if (it == "f") 0 else 30 * MIN, completed = it != "f") }
        compose.setContent { QueueContent(episodes, emptyList(),
            PlaybackState(episodeId = "f", durationMs = 30 * MIN, playing = true, queue = ids),
            {}, {}, {}, {}, { _, _ -> }, {}) }
        // Visible without any scrolling by the test.
        compose.onNodeWithText("Episode f").assertIsDisplayed()
        compose.onNodeWithText("Now playing").assertIsDisplayed()
        compose.onNodeWithText("Nothing up next").assertIsDisplayed()
        compose.onNodeWithTag("queue-list").performScrollToNode(hasText("History"))
        compose.onNodeWithText("History").assertIsDisplayed()
    }

    @Config(qualifiers = "w411dp-h2400dp")
    @Test fun `queue sections follow the actual playlist order`() {
        val ids = listOf("a", "b", "c", "d")
        compose.setContent { QueueContent(ids.map { episode(it) }, emptyList(),
            PlaybackState(episodeId = "b", durationMs = 30 * MIN, queue = ids),
            {}, {}, {}, {}, { _, _ -> }, {}) }
        val order = listOf("History", "Episode a", "Now playing", "Episode b", "Up next", "Episode c", "Episode d")
            .map { compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top }
        assertEquals(order.sorted(), order)
        compose.onAllNodesWithText("Nothing up next").assertCountEquals(0)
    }

    @Test fun `without a history section the queue starts at its controls`() {
        compose.setContent { QueueContent(listOf(episode("a"), episode("b")), emptyList(),
            PlaybackState(episodeId = "a", durationMs = 30 * MIN, queue = listOf("a", "b")),
            {}, {}, {}, {}, { _, _ -> }, {}) }
        compose.onNodeWithText("Clear queue").assertIsDisplayed()
        compose.onAllNodesWithText("History").assertCountEquals(0)
    }
}
