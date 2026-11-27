package dev.distillpod.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.distillpod.android.features.*
import dev.distillpod.android.ui.theme.DistillTheme
import dev.distillpod.android.video.YOUTUBE_LINK_UNSUPPORTED
import dev.distillpod.android.video.YOUTUBE_UNAVAILABLE
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The screens of a build without YouTube sources (#319) offer no YouTube entry point. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h823dp-xxhdpi")
class YouTubeOffScreensTest {
    @get:Rule val compose = createComposeRule()

    private fun search(query: String, youtube: Boolean) =
        compose.setContent {
            DistillTheme { Box(Modifier.size(384.dp, 640.dp)) {
                SearchScreen(query, SearchState(), emptyList(), emptyList(), emptySet(), {}, {}, {}, {}, {}, {}, youtube = youtube)
            } }
        }

    @Test fun `with YouTube sources search keeps its format choice and YouTube links`() {
        search("history", youtube = true)
        compose.onNodeWithTag("search-format").assertExists()
        compose.onNodeWithTag("Podcast search results").performScrollToNode(hasText("Add RSS feed"))
        compose.onNodeWithText("Add RSS feed").performClick()
        compose.onNodeWithText("Paste a public RSS feed, YouTube channel, playlist or video link.").assertExists()
    }

    @Test fun `search has no format choice and no YouTube wording`() {
        search("history", youtube = false)
        compose.onNodeWithTag("search-format").assertDoesNotExist()
        compose.onNodeWithText("Video").assertDoesNotExist()
        compose.onNodeWithTag("Podcast search results").performScrollToNode(hasText("Add RSS feed"))
        compose.onNodeWithText("Add RSS feed").performClick()
        compose.onNodeWithText("Paste a public RSS feed link.").assertExists()
        compose.onNodeWithText("RSS feed URL").assertExists()
        compose.onAllNodes(hasText("YouTube", substring = true)).assertCountEquals(0)
    }

    @Test fun `a pasted YouTube link is answered and cannot be added`() {
        search("https://www.youtube.com/@distillpod", youtube = false)
        compose.onNodeWithContentDescription("Preview RSS feed").performClick()
        compose.onNodeWithText(YOUTUBE_LINK_UNSUPPORTED).assertExists()
        compose.onNodeWithText("Add feed").assertIsNotEnabled()
        compose.onNodeWithText("Browse episodes").assertIsNotEnabled()
        compose.onNodeWithTag("Feed address").performTextReplacement("https://example.test/feed.xml")
        compose.onNodeWithText(YOUTUBE_LINK_UNSUPPORTED).assertDoesNotExist()
        compose.onNodeWithText("Add feed").assertIsEnabled()
        compose.onNodeWithText("Browse episodes").assertIsEnabled()
    }

    @Test fun `the directory prompt does not promise videos`() {
        compose.setContent { DistillTheme { DirectorySearchPrompt("history", true, youtube = false) {} } }
        compose.onNodeWithText("Find new shows online").assertExists()
    }

    @Test fun `a restored YouTube show explains that it is kept but unavailable`() {
        compose.setContent { DistillTheme { UnavailableSourceNotice() } }
        compose.onNodeWithText(YOUTUBE_UNAVAILABLE).assertIsDisplayed()
    }
}
