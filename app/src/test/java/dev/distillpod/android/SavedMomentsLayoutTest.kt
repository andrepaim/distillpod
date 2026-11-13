package dev.distillpod.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.distillpod.android.data.DistillationEntity
import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.android.features.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h823dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SavedMomentsLayoutTest {
    @get:Rule val compose = createComposeRule()

    // Long synthetic text exercises the card's line limits rather than a best case.
    private fun item(n: Int, state: String = "succeeded") = SavedMomentItem(
        id = "m$n", state = state, excerpt = "Synthetic source passage $n.",
        insight = "Moment $n: a small, repeated practice of attention changes how we listen, what we notice and which ideas we carry into the rest of the week.",
        quote = "We get better at listening by deciding, again and again, to make room for curiosity before we make room for an answer $n.",
        episodeTitle = "A deliberately long synthetic episode title about attention, curiosity and practice $n",
        showTitle = "The Synthetic Listening Room", artworkUrl = null, startMs = 40_000L * n, endMs = 40_000L * n + 21_000)

    private fun content(items: List<SavedMomentItem>, tab: SavedTab = SavedTab.Moments, onTab: (SavedTab) -> Unit = {},
        query: String = "", onQuery: (String) -> Unit = {}, onOpen: (String) -> Unit = {}) {
        compose.setContent {
            DistillTheme {
                // A 1080x2316 phone at its default 450 dpi is about 384x823 dp. Subtract the status bar,
                // app bar, mini-player, navigation bar and gesture area to get the Saved page's viewport.
                Box(Modifier.size(384.dp, 520.dp)) {
                    SavedContent(tab, onTab, items, items.size, query, onQuery, onOpen, {}) { Text("Posts fixture") }
                }
            }
        }
    }

    @Test fun `at least two moment cards are fully visible on a 1080x2316 phone`() {
        content((1..6).map { item(it) })
        val viewport = compose.onNodeWithTag("saved-list").getBoundsInRoot()
        val cards = compose.onAllNodesWithTag("saved-moment-card")
        for (index in 0..1) {
            val card = cards[index].getBoundsInRoot()
            assertTrue("card $index ends at ${card.bottom}, below the ${viewport.bottom} viewport", card.bottom <= viewport.bottom)
        }
    }

    @Test fun `status chip appears only for moments that are not finished`() {
        content(listOf(item(1), item(2, "running"), item(3, "imported"), item(4, "failed")))
        compose.onAllNodesWithText("Saved", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("Imported", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("Finding the idea", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("saved-list").performScrollToNode(hasText("Needs retry"))
        compose.onNodeWithText("Needs retry", useUnmergedTree = true).assertExists()
    }

    @Test fun `posts are a segment of the Saved page rather than a floating button`() {
        var tab by mutableStateOf(SavedTab.Moments)
        compose.setContent { DistillTheme { SavedContent(tab, { tab = it }, listOf(item(1)), 1, "", {}, {}, {}) { Text("Posts fixture") } } }
        compose.onNodeWithText("Your posts").assertDoesNotExist()
        compose.onNodeWithText("Moments").assertIsSelected()
        compose.onNodeWithText("Posts").assertIsNotSelected().performClick()
        assertEquals(SavedTab.Posts, tab)
        compose.onNodeWithText("Posts").assertIsSelected()
        compose.onNodeWithText("Posts fixture").assertIsDisplayed()
        compose.onNodeWithText("Search saved moments").assertDoesNotExist()
    }

    @Test fun `search field scrolls away with the list`() {
        content((1..8).map { item(it) })
        compose.onNodeWithText("Search saved moments").assertIsDisplayed()
        compose.onNodeWithTag("saved-list").performScrollToNode(hasText("Moment 8", substring = true))
        compose.onNodeWithText("Search saved moments").assertIsNotDisplayed()
    }

    @Test fun `opening a card names the moment`() {
        var opened: String? = null
        content(listOf(item(1), item(2)), onOpen = { opened = it })
        compose.onNodeWithText("Moment 2", substring = true).performClick()
        assertEquals("m2", opened)
    }

    @Test fun `search matches insight, quote, source passage, episode and show`() {
        val show = PodcastEntity("show", "https://example.test/feed", "Morning Signals")
        val ep = EpisodeEntity("ep", "show", "guid", "Practice and patience", "https://example.test/audio")
        val moment = DistillationEntity("m", "ep", 0, 20_000, excerpt = "The passage mentions gardening.", quote = "Slow down.", insight = "Patience compounds.")
        val items = savedMomentItems(listOf(moment), listOf(ep), listOf(show))
        assertEquals("Practice and patience", items.single().episodeTitle)
        listOf("compounds", "slow", "GARDENING", "patience", "morning signals", "").forEach { assertTrue(it, items.single().matches(it)) }
        assertFalse(items.single().matches("unrelated"))
    }

    @Test fun `only unfinished states have a status`() {
        assertNull(momentStatus("succeeded"))
        assertNull(momentStatus("imported"))
        assertEquals("Finding the idea", momentStatus("running"))
        assertEquals("Waiting for transcript", momentStatus("waiting_transcript"))
        assertEquals("No insight yet", momentStatus("saved"))
    }
}
