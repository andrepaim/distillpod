package dev.distillpod.android

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import dev.distillpod.android.data.EpisodeEntity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import dev.distillpod.android.features.QueueContent
import dev.distillpod.android.features.QueueUndo
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
    private val episodes = listOf("a", "b", "c", "d", "e").map { EpisodeEntity(it, "show", it, "Episode $it", "https://example.test/$it") }

    private fun row(id: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-row-$id"))
        return compose.onNodeWithTag("queue-row-$id")
    }
    private fun customActions(id: String) =
        row(id).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
    private fun performCustomAction(id: String, label: String) {
        val action = customActions(id).single { it.label == label }
        compose.runOnIdle { assertTrue(action.action()) }
    }
    /** Robolectric sizes the sheet's window shorter than its content, so sheet buttons are pressed through their click action. */
    private fun SemanticsNodeInteraction.press() = performSemanticsAction(SemanticsActions.OnClick)
    private fun openMenu(id: String) {
        compose.onNodeWithTag("queue-list").performScrollToNode(hasContentDescription("Queue actions for Episode $id"))
        compose.onNodeWithContentDescription("Queue actions for Episode $id").assertIsDisplayed().performClick()
    }

    @Test fun `rows have no separate move or remove buttons`() {
        compose.setContent { QueueContent(episodes, emptyList(), PlaybackState(episodeId = "b", queue = listOf("a", "b", "c")),
            {}, {}, {}, {}, { _, _ -> }, {}) }
        listOf("a", "b", "c").forEach { id ->
            row(id)
            compose.onAllNodesWithContentDescription("Move Episode $id up").assertCountEquals(0)
            compose.onAllNodesWithContentDescription("Move Episode $id down").assertCountEquals(0)
            compose.onAllNodesWithContentDescription("Remove Episode $id from queue").assertCountEquals(0)
            compose.onNodeWithContentDescription("Queue actions for Episode $id").assertIsDisplayed()
        }
    }

    @Test fun `a row keeps play and its overflow menu on one line beside the episode`() {
        compose.setContent { QueueContent(episodes, emptyList(), PlaybackState(episodeId = "b", queue = listOf("a", "b", "c")),
            {}, {}, {}, {}, { _, _ -> }, {}) }
        val rowBounds = row("b").fetchSemanticsNode().boundsInRoot
        // Only Play and the overflow menu are separate controls inside the row.
        val controls = compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("queue-row-b")), useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals(2, controls.size)
        val play = compose.onNodeWithContentDescription("Play Episode b").fetchSemanticsNode().boundsInRoot
        val menu = compose.onNodeWithContentDescription("Queue actions for Episode b").fetchSemanticsNode().boundsInRoot
        assertEquals(setOf(play, menu), controls.toSet())
        // Both sit vertically centred in the row, rather than in a second row of buttons below it.
        listOf(play, menu).forEach { assertEquals(rowBounds.center.y, it.center.y, 1f) }
        assertTrue(play.right <= menu.left)
        val titleBounds = compose.onNodeWithText("Episode b", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Controls sit beside the title at default text size", play.left >= titleBounds.right)
    }

    @Test fun `custom accessibility actions follow full playlist boundaries and move by identity`() {
        val moves = mutableListOf<Pair<String, Int>>()
        val removed = mutableListOf<String>()
        compose.setContent { QueueContent(episodes, emptyList(), PlaybackState(episodeId = "b", queue = listOf("a", "b", "c")),
            {}, {}, {}, {}, { id, offset -> moves += id to offset }, {}, { removed += it }) }
        assertEquals(listOf("Move down", "Remove from queue"), customActions("a").map { it.label })
        assertEquals(listOf("Move up", "Move down", "Remove from queue"), customActions("b").map { it.label })
        assertEquals(listOf("Move up", "Remove from queue"), customActions("c").map { it.label })
        performCustomAction("b", "Move up")
        performCustomAction("b", "Move down")
        performCustomAction("c", "Remove from queue")
        assertEquals(listOf("b" to -1, "b" to 1), moves)
        assertEquals(listOf("c"), removed)
    }

    @Test fun `overflow menu offers the same actions with disabled boundaries`() {
        val moves = mutableListOf<Pair<String, Int>>()
        val removed = mutableListOf<String>()
        compose.setContent { QueueContent(episodes, emptyList(), PlaybackState(episodeId = "b", queue = listOf("a", "b", "c")),
            {}, {}, {}, {}, { id, offset -> moves += id to offset }, {}, { removed += it }) }
        openMenu("a")
        compose.onNodeWithText("Move up").assertIsNotEnabled()
        compose.onNodeWithText("Move down").assertIsEnabled().performClick()
        compose.onAllNodesWithText("Move up").assertCountEquals(0)
        openMenu("c")
        compose.onNodeWithText("Move down").assertIsNotEnabled()
        compose.onNodeWithText("Move up").assertIsEnabled().performClick()
        openMenu("b")
        compose.onNodeWithText("Remove from queue").assertIsEnabled().performClick()
        assertEquals(listOf("a" to 1, "c" to -1), moves)
        assertEquals(listOf("b"), removed)
    }

    @Test fun `clear requires confirmation and cancellation retains queue`() {
        var clears=0
        compose.setContent { QueueContent(episodes, emptyList(), PlaybackState(episodeId="a",queue=listOf("a")),
            {}, {}, {}, {}, { _, _ -> }, { clears++ }) }
        assertEquals(listOf("Remove from queue"), customActions("a").map { it.label })
        compose.onNodeWithContentDescription("Clear queue").performClick()
        compose.onNodeWithText("Clear queue and stop playback?").assertIsDisplayed()
        assertEquals(0, clears)
        compose.onNodeWithText("Cancel").press()
        assertEquals(0, clears)
        compose.onNodeWithContentDescription("Clear queue").performClick()
        compose.onNodeWithText("Clear and stop").press()
        assertEquals(1, clears)
    }

    @Config(qualifiers = "w320dp-h480dp")
    @Test fun `large text on a small screen keeps play and every queue action reachable`() {
        val moves = mutableListOf<Pair<String, Int>>()
        val long = episodes.map { it.copy(title = "Episode ${it.id} with a very long title that keeps going well past the width of a small phone") }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                QueueContent(long, emptyList(), PlaybackState(episodeId="c",queue=listOf("a","b","c")),
                    {}, {}, {}, {}, { id, offset -> moves += id to offset }, {})
            }
        }
        val title = long[2].title
        compose.onNodeWithTag("queue-list").performScrollToNode(hasContentDescription("Queue actions for $title"))
        val titleBounds = compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val play = compose.onNodeWithContentDescription("Play $title").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        // Enlarged text keeps the full row width for the episode; the controls move below it.
        assertTrue("Controls must sit below the title at large text", play.top >= titleBounds.bottom)
        compose.onNodeWithContentDescription("Queue actions for $title").assertIsDisplayed().performClick()
        compose.onNodeWithText("Move down").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Remove from queue").assertIsDisplayed()
        compose.onNodeWithText("Move up").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(listOf("c" to -1), moves)
        assertEquals(listOf("Move up", "Remove from queue"), customActions("c").map { it.label })
        compose.onNodeWithTag("queue-list").performScrollToNode(hasContentDescription("Clear queue"))
        compose.onNodeWithContentDescription("Clear queue").assertIsDisplayed().performClick()
        compose.onNodeWithText("Cancel").assertIsDisplayed().press()
    }

    @Test fun `external queue changes update boundaries and empty state dismisses clear`() {
        val state=mutableStateOf(PlaybackState(episodeId="a",queue=listOf("a","b")))
        compose.setContent { QueueContent(episodes,emptyList(),state.value,{}, {}, {}, {}, { _, _ -> }, {}) }
        assertEquals(listOf("Move down", "Remove from queue"), customActions("a").map { it.label })
        compose.onNodeWithContentDescription("Clear queue").performClick()
        compose.runOnIdle { state.value=PlaybackState(episodeId="c",queue=listOf("c","b")) }
        compose.onNodeWithText("Clear queue and stop playback?").assertDoesNotExist()
        assertEquals(listOf("Move up", "Remove from queue"), customActions("b").map { it.label })
        compose.onNodeWithContentDescription("Clear queue").performClick()
        compose.runOnIdle { state.value=PlaybackState() }
        compose.onNodeWithText("Your queue is clear").assertIsDisplayed()
        compose.onNodeWithText("Clear queue and stop playback?").assertDoesNotExist()
        compose.runOnIdle { state.value=PlaybackState(episodeId="b",queue=listOf("b")) }
        compose.onNodeWithText("Clear queue and stop playback?").assertDoesNotExist()
        assertEquals(listOf("Remove from queue"), customActions("b").map { it.label })
        openMenu("b")
        compose.onNodeWithText("Move up").assertIsNotEnabled()
        compose.onNodeWithText("Move down").assertIsNotEnabled()
    }

    /** A playlist that applies the ViewModel's by-identity edits like the playback layer does. */
    private inner class LiveQueue(ids: List<String>, current: String) {
        val state = mutableStateOf(PlaybackState(episodeId = current, queue = ids))
        val moves = mutableListOf<Pair<String, Int>>()
        val snackbar = SnackbarHostState()
        private fun edit(change: MutableList<String>.() -> Unit) { state.value = state.value.copy(queue = state.value.queue.toMutableList().apply(change)) }
        fun move(id: String, offset: Int) { moves += id to offset; edit { val at = indexOf(id); val to = at + offset; if (at >= 0 && to in indices) add(to, removeAt(at)) } }
        fun remove(id: String) = edit { remove(id) }
        fun enqueue(id: String, next: Boolean) = edit {
            remove(id); val at = indexOf(state.value.episodeId)
            if (next && at >= 0) add(at + 1, id) else add(id)
        }
        fun show() = compose.setContent {
            Box(Modifier.fillMaxSize()) {
                QueueContent(episodes, emptyList(), state.value, {}, {}, {}, {}, ::move, {}, ::remove, QueueUndo(snackbar, ::enqueue))
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    @Config(qualifiers = "w411dp-h1200dp")
    @Test fun `swiping an up next row removes it and Undo puts it back at the same index`() {
        val live = LiveQueue(listOf("a", "b", "c", "d", "e"), current = "b")
        live.show()
        row("d").performTouchInput { swipeLeft() }
        compose.waitUntil(5000) { live.state.value.queue == listOf("a", "b", "c", "e") }
        compose.onNodeWithText("Removed “Episode d”").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5000) { live.state.value.queue == listOf("a", "b", "c", "d", "e") }
        // History rows swipe too; the restore goes through enqueue and single-step moves by id.
        row("a").performTouchInput { swipeLeft() }
        compose.waitUntil(5000) { live.state.value.queue == listOf("b", "c", "d", "e") }
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5000) { live.state.value.queue == listOf("a", "b", "c", "d", "e") }
        assertEquals(listOf("d" to -1, "a" to -1), live.moves)
    }

    @Config(qualifiers = "w411dp-h1200dp")
    @Test fun `the playing episode does not swipe away and its menu remove offers no undo`() {
        val live = LiveQueue(listOf("a", "b", "c"), current = "b")
        live.show()
        row("b").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(listOf("a", "b", "c"), live.state.value.queue)
        performCustomAction("b", "Remove from queue")
        compose.waitUntil(5000) { live.state.value.queue == listOf("a", "c") }
        compose.onAllNodesWithText("Undo").assertCountEquals(0)
    }

    @Config(qualifiers = "w411dp-h1200dp")
    @Test fun `dragging an up next handle moves that episode by id through single steps`() {
        val live = LiveQueue(listOf("a", "b", "c", "d", "e"), current = "b")
        live.show()
        val height = row("c").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("queue-drag-c", useUnmergedTree = true).performTouchInput {
            down(center)
            repeat(12) { moveBy(Offset(0f, height * 1.2f / 12)) }
            up()
        }
        compose.waitUntil(5000) { live.state.value.queue == listOf("a", "b", "d", "c", "e") }
        assertEquals(listOf("c" to 1), live.moves)
        // Back up past both neighbours: two steps up, resolved by the same id.
        compose.onNodeWithTag("queue-drag-c", useUnmergedTree = true).performTouchInput {
            down(center)
            repeat(12) { moveBy(Offset(0f, -height * 1.2f / 12)) }
            up()
        }
        compose.waitUntil(5000) { live.state.value.queue == listOf("a", "b", "c", "d", "e") }
        assertEquals(listOf("c" to 1, "c" to -1), live.moves)
        // History and the playing episode have no handle; TalkBack keeps Move up and Move down.
        compose.onAllNodesWithTag("queue-drag-a", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("queue-drag-b", useUnmergedTree = true).assertCountEquals(0)
    }
}
