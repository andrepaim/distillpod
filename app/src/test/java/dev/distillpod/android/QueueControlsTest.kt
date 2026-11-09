package dev.distillpod.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.features.QueueContent
import dev.distillpod.android.playback.PlaybackState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueueControlsTest {
    @get:Rule val compose = createComposeRule()
    private val episodes = listOf("a", "b", "c").map { EpisodeEntity(it, "show", it, "Episode $it", "https://example.test/$it") }

    @Test fun `all playlist rows including current have actual boundary controls`() {
        val moves = mutableListOf<Pair<String, Int>>()
        compose.setContent { QueueContent(episodes, emptyList(), PlaybackState(episodeId="b", queue=listOf("a","b","c")),
            {}, {}, {}, {}, { id, offset -> moves += id to offset }, {}) }
        compose.onNodeWithTag("queue-list").performScrollToNode(hasContentDescription("Move Episode a up"))
        compose.onNodeWithContentDescription("Move Episode a up").assertIsNotEnabled()
        compose.onNodeWithTag("queue-list").performScrollToNode(hasContentDescription("Move Episode b up"))
        compose.onNodeWithContentDescription("Move Episode b up").assertIsEnabled().performClick()
        compose.onNodeWithTag("queue-list").performScrollToNode(hasContentDescription("Move Episode b down"))
        compose.onNodeWithContentDescription("Move Episode b down").assertIsEnabled().performClick()
        compose.onNodeWithTag("queue-list").performScrollToNode(hasContentDescription("Move Episode c down"))
        compose.onNodeWithContentDescription("Move Episode c down").assertIsNotEnabled()
        assertEquals(listOf("b" to -1, "b" to 1), moves)
    }

    @Test fun `clear requires confirmation and cancellation retains queue`() {
        var clears=0
        compose.setContent { QueueContent(episodes, emptyList(), PlaybackState(episodeId="a",queue=listOf("a")),
            {}, {}, {}, {}, { _, _ -> }, { clears++ }) }
        compose.onNodeWithContentDescription("Move Episode a up").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move Episode a down").assertIsNotEnabled()
        compose.onNodeWithText("Clear queue").performClick()
        compose.onNodeWithText("Clear queue and stop playback?").assertIsDisplayed()
        assertEquals(0, clears)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, clears)
        compose.onNodeWithText("Clear queue").performClick()
        compose.onNodeWithText("Clear and stop").performClick()
        assertEquals(1, clears)
    }

    @Test fun `large text controls remain reachable and current marker stays on its row`() {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 1.6f)
            ) {
                QueueContent(episodes, emptyList(), PlaybackState(episodeId="c",queue=listOf("a","b","c")),
                    {}, {}, {}, {}, { _, _ -> }, {})
            }
        }
        compose.onNodeWithTag("queue-list").performScrollToNode(hasContentDescription("Move Episode c up"))
        compose.onNodeWithContentDescription("Move Episode c up").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("Move Episode c down").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("queue-list").performScrollToNode(hasText("Clear queue"))
        compose.onNodeWithText("Clear queue").assertIsDisplayed().performClick()
        compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
    }

    @Test fun `external queue changes update boundaries and empty state dismisses clear`() {
        val state=mutableStateOf(PlaybackState(episodeId="a",queue=listOf("a","b")))
        compose.setContent { QueueContent(episodes,emptyList(),state.value,{}, {}, {}, {}, { _, _ -> }, {}) }
        compose.onNodeWithText("Clear queue").performClick()
        compose.runOnIdle { state.value=PlaybackState(episodeId="c",queue=listOf("c","b")) }
        compose.onNodeWithText("Clear queue and stop playback?").assertDoesNotExist()
        compose.onNodeWithText("Clear queue").performClick()
        compose.runOnIdle { state.value=PlaybackState() }
        compose.onNodeWithText("Your queue is clear").assertIsDisplayed()
        compose.onNodeWithText("Clear queue and stop playback?").assertDoesNotExist()
        compose.runOnIdle { state.value=PlaybackState(episodeId="b",queue=listOf("b")) }
        compose.onNodeWithText("Clear queue and stop playback?").assertDoesNotExist()
        compose.onNodeWithContentDescription("Move Episode b up").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move Episode b down").assertIsNotEnabled()
    }
}
