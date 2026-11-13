package dev.distillpod.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.distillpod.android.data.PodcastCandidate
import dev.distillpod.android.features.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #179: the Search tab shows library matches from one field, before and alongside the directory. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h823dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibrarySearchScreenTest {
    @get:Rule val compose = createComposeRule()

    private val terms = listOf("carbon")
    private val matches = LibraryMatches(terms,
        shows = listOf(ShowMatch("p1", "Carbon Weekly", "Studio North", null, video = false)),
        episodes = (1..5).map { EpisodeMatch("e$it", "Carbon episode $it", "Carbon Weekly", null, publishedAt = 0, video = it == 5) },
        passages = listOf(PassageMatch("e1", "Carbon episode 1", "Carbon Weekly", null, 61_000, "…the carbon tax returns…", 3, video = false)),
        moments = listOf(MomentMatch("m1", "Carbon pricing works when it is predictable.", "Carbon episode 1", "Carbon Weekly", 120_000, null, video = false)))

    private val library = mutableStateOf(LibrarySearchState(terms, matches = matches))

    private fun screen(query: String = "carbon", initial: LibrarySearchState = library.value,
        state: SearchState = SearchState(), recent: List<String> = emptyList(), onSearch: (String) -> Unit = {}, onOpen: (String) -> Unit = {}) {
        library.value = initial
        compose.setContent {
            DistillTheme {
                Box(Modifier.size(384.dp, 640.dp)) {
                    SearchScreen(query, state, recent, emptyList(), emptySet(), {}, onSearch, {}, {}, {}, {}, {}, library.value, onOpen)
                }
            }
        }
    }

    private val results get() = compose.onNodeWithTag("Podcast search results")

    @Test fun `library sections are headings with collapsed rows that expand`() {
        screen()
        listOf("In your library", "Shows", "Episodes", "Transcripts", "Saved moments").forEach { title ->
            results.performScrollToNode(hasText(title))
            compose.onNode(hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
        }
        results.performScrollToNode(hasText("Carbon episode 3"))
        compose.onNodeWithText("Carbon episode 4").assertDoesNotExist()
        results.performScrollToNode(hasText("Show all 5 episodes"))
        compose.onNodeWithText("Show all 5 episodes").performClick()
        results.performScrollToNode(hasText("Carbon episode 5"))
        compose.onNodeWithText("Carbon episode 5").assertExists()
        results.performScrollToNode(hasText("Results from your library stay on this phone."))
        compose.onNodeWithText("Results from your library stay on this phone.").assertExists()
    }

    @Test fun `each kind of match opens its own page`() {
        val opened = mutableListOf<String>()
        screen(onOpen = { opened += it })
        listOf("Carbon Weekly" to "podcast:p1", "Carbon episode 2" to "episode:e2",
            "…the carbon tax returns…" to "transcript:e1@61000", "Carbon pricing works when it is predictable." to "capture:m1").forEach { (text, _) ->
            results.performScrollToNode(hasText(text))
            compose.onAllNodesWithText(text).onFirst().performClick()
        }
        assertEquals(listOf("podcast:p1", "episode:e2", "transcript:e1@61000", "capture:m1"), opened)
        results.performScrollToNode(hasText("1:01 · 3 matches", substring = true))
        compose.onNode(hasClickAction() and hasText("…the carbon tax returns…")).assertExists()
    }

    @Test fun `loading, empty and failed library states`() {
        screen(initial = LibrarySearchState(terms, loading = true))
        compose.onNodeWithText("Searching your library…").assertExists()
        library.value = LibrarySearchState(terms, matches = LibraryMatches(terms))
        compose.onNodeWithText("Nothing in your library matches “carbon”.").assertExists()
        library.value = LibrarySearchState(terms, loading = true, matches = matches)
        compose.onNodeWithText("Results from your library stay on this phone.").assertExists()
        compose.onNodeWithText("Studio North").assertExists()
        library.value = LibrarySearchState(terms, failed = true)
        compose.onNodeWithText("Library search could not finish.", substring = true).assertExists()
    }

    @Test fun `the directory is searched only on request`() {
        val searched = mutableListOf<String>()
        screen(onSearch = { searched += it })
        assertTrue(searched.isEmpty())
        results.performScrollToNode(hasText("Search the podcast directory", substring = true))
        compose.onNodeWithText("Search the podcast directory", substring = true).performClick()
        assertEquals(listOf("carbon"), searched)
    }

    @Test fun `directory results follow library matches and the format filter applies to both`() {
        screen(state = SearchState(results = listOf(PodcastCandidate("Carbon Directory Show", "Someone", "https://example.test/dir", null)), submittedQuery = "carbon"))
        results.performScrollToNode(hasText("Carbon Directory Show"))
        compose.onNodeWithText("Carbon Directory Show").assertExists()
        compose.onNodeWithText("Video", substring = false).performClick()
        compose.onNodeWithText("Carbon Directory Show").assertDoesNotExist()
        compose.onNodeWithText("Studio North").assertDoesNotExist()
        results.performScrollToNode(hasText("Carbon episode 5"))
        compose.onNodeWithText("Carbon episode 1").assertDoesNotExist()
    }

    @Test fun `an empty field still offers recent searches and topics`() {
        screen(query = "", initial = LibrarySearchState(), recent = listOf("History"))
        compose.onNodeWithText("Recent searches").assertExists()
        compose.onNodeWithText("In your library").assertDoesNotExist()
        compose.onNodeWithText("Explore topics").assertExists()
    }
}
