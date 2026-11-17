package dev.distillpod.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.android.features.FeedProblems
import dev.distillpod.android.features.feedProblemKey
import dev.distillpod.android.features.undismissedFeedProblems
import dev.distillpod.android.ui.theme.DistillTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #264: the Library's refresh warning swipes right to dismiss, without touching the shows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h823dp-xxhdpi")
class FeedProblemsDismissTest {
    @get:Rule val compose = createComposeRule()
    private fun failing(id: String, refreshedAt: Long = 100) =
        PodcastEntity(id, "https://example.test/$id", "Show $id", error = "Couldn't reach the feed.", refreshedAt = refreshedAt)

    /** The Library page's arrangement: shows above, the warning, then episodes, all in one scrolling list. */
    private inner class Library(problems: List<PodcastEntity>) {
        val problems = mutableStateOf(problems)
        val dismissed = mutableStateOf(emptySet<String>())
        val calls = mutableListOf<String>()
        lateinit var list: LazyListState

        fun show() = compose.setContent {
            list = rememberLazyListState()
            val visible = undismissedFeedProblems(problems.value, dismissed.value)
            DistillTheme {
                LazyColumn(Modifier.fillMaxSize().testTag("library"), state = list) {
                    item(key = "shows") { Box(Modifier.height(120.dp).testTag("shows")) { Text("Your shows") } }
                    if (visible.isNotEmpty()) item(key = "feed-problems") {
                        // As the ViewModel does, a dismissal records every failure the Library currently has.
                        FeedProblems(visible, showPage = false, refreshing = false, onRetry = { calls += "retry:$it" }, onOpen = {}, onUnsubscribe = {},
                            onDismiss = { calls += "dismiss"; dismissed.value = problems.value.mapTo(HashSet(), ::feedProblemKey) })
                    }
                    items(30, key = { "episode-$it" }) { Box(Modifier.height(96.dp).testTag("episode-$it")) { Text("Episode $it") } }
                }
            }
        }
    }

    private fun warning() = compose.onNodeWithTag("feed-problems")

    @Test fun `a completed right swipe dismisses the warning and collapses its space`() {
        val library = Library(listOf(failing("a"), failing("b")))
        library.show()
        val below = compose.onNodeWithTag("episode-0").getBoundsInRoot().top
        val warningTop = warning().getBoundsInRoot().top
        warning().performTouchInput { swipeRight() }
        compose.waitForIdle()
        warning().assertDoesNotExist()
        assertEquals(listOf("dismiss"), library.calls)
        // The episodes move up into the warning's place.
        assertEquals(warningTop, compose.onNodeWithTag("episode-0").getBoundsInRoot().top)
        assertTrue(below > warningTop)
        // The shows themselves are untouched: still failing, still listed by the page's data.
        assertEquals(listOf("a", "b"), library.problems.value.map { it.id })
    }

    @Test fun `a short or leftward swipe leaves the warning in place`() {
        val library = Library(listOf(failing("a"), failing("b")))
        library.show()
        val bounds = warning().getBoundsInRoot()
        // A slow drag shorter than the threshold settles back.
        warning().performTouchInput { swipe(centerLeft + Offset(20f, 0f), centerLeft + Offset(60f, 0f), durationMillis = 800) }
        warning().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        warning().assertIsDisplayed()
        assertEquals(bounds, warning().getBoundsInRoot())
        assertTrue(library.calls.isEmpty())
    }

    @Test fun `details and actions still respond to taps and the list still scrolls through the warning`() {
        val library = Library(listOf(failing("a"), failing("b")))
        library.show()
        compose.onNodeWithText("Show details").performClick()
        compose.onNodeWithContentDescription("Retry Show a").performClick()
        assertEquals(listOf("retry:a"), library.calls)
        warning().performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue("list scrolled", library.list.firstVisibleItemIndex > 0 || library.list.firstVisibleItemScrollOffset > 0)
        assertEquals(listOf("retry:a"), library.calls)
    }

    @Test fun `TalkBack offers a dismiss action on the warning`() {
        val library = Library(listOf(failing("a"), failing("b")))
        library.show()
        val action = compose.onNode(hasText("2 shows couldn't refresh", substring = true)).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.CustomActions).orEmpty().single { it.label == "Dismiss warning" }
        compose.runOnIdle { assertTrue(action.action()) }
        compose.waitForIdle()
        warning().assertDoesNotExist()
        assertEquals(listOf("dismiss"), library.calls)
    }

    @Test fun `a dismissed warning stays hidden for the same failures and returns, unswiped, for a new one`() {
        val library = Library(listOf(failing("a"), failing("b")))
        library.show()
        warning().performTouchInput { swipeRight() }
        compose.waitForIdle()
        // Retries that fail again, and a show that recovers, keep it hidden.
        library.problems.value = listOf(failing("a"), failing("b"))
        compose.waitForIdle(); warning().assertDoesNotExist()
        library.problems.value = listOf(failing("b"))
        compose.waitForIdle(); warning().assertDoesNotExist()
        // Another show failing brings back a warning for that show only, in place and not pre-swiped.
        library.problems.value = listOf(failing("b"), failing("c"))
        compose.waitForIdle()
        warning().assertIsDisplayed()
        compose.onNode(hasText("Show c") and hasAnyAncestor(hasTestTag("feed-problems"))).assertIsDisplayed()
        assertEquals(compose.onNodeWithTag("shows").getBoundsInRoot().bottom, warning().getBoundsInRoot().top)
        assertEquals(listOf("dismiss"), library.calls)
    }
}
