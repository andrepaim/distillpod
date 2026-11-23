package dev.distillpod.android

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.android.features.HomeShowRail
import dev.distillpod.android.ui.theme.DistillTheme
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #276: Home's show row displays its order, including a show that just moved to the front. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h823dp-xxhdpi")
class HomeShowRailTest {
    @get:Rule val compose = createComposeRule()
    private fun show(id: String) = PodcastEntity(id, "https://example.test/$id", "Show $id")
    private val initial = ('a'..'h').map { show(it.toString()) }
    private val shows = mutableStateOf(initial)
    private val opened = mutableListOf<String>()
    private lateinit var row: LazyListState

    private fun setContent() = compose.setContent {
        DistillTheme { row = rememberLazyListState(); HomeShowRail(shows.value, { opened += it.id }, state = row) }
    }
    /** The show at the row's scroll position; the start padding can also reveal part of the previous one. */
    private fun firstVisible() = row.layoutInfo.visibleItemsInfo.first { it.index == row.firstVisibleItemIndex }.key

    @Test fun `a show moved to the front is shown first when the row is at its start`() {
        setContent()
        assertEquals("a", firstVisible())
        // For example, a show pinned in Shows or with a newer episode.
        shows.value = listOf(show("h")) + initial.dropLast(1)
        compose.waitForIdle()
        assertEquals("h", firstVisible())
        assertEquals(0, row.firstVisibleItemIndex)
        compose.onNodeWithText("Show h").assertIsDisplayed().performClick()
        assertEquals(listOf("h"), opened)
    }

    @Test fun `a row the owner scrolled is not sent back to its start when the order changes`() {
        setContent()
        compose.runOnIdle { runBlocking { row.scrollBy(row.layoutInfo.visibleItemsInfo[3].offset.toFloat()) } }
        compose.waitForIdle()
        assertEquals("d", firstVisible())
        shows.value = listOf(show("h")) + initial.dropLast(1)
        compose.waitForIdle()
        assertTrue(row.firstVisibleItemIndex >= 3)
    }
}
