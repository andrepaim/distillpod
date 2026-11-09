package dev.distillpod.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.android.features.EpisodeShowLink
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpisodeShowLinkTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showActionNamesAndOpensTheCorrespondingShow() {
        var destination: String? = null
        compose.setContent { EpisodeShowLink(PodcastEntity("selected-show", "https://example.test/feed", "Selected show")) { destination = it } }
        compose.onNodeWithText("View show", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("View show: Selected show").assertHasClickAction().performClick()
        assertEquals("podcast:selected-show", destination)
    }

    @Test fun unavailableShowDoesNotExposeAnActionAndCanRecover() {
        val show = mutableStateOf<PodcastEntity?>(null)
        var destination: String? = null
        compose.setContent { EpisodeShowLink(show.value) { destination = it } }
        compose.onNodeWithText("Show unavailable").assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        assertNull(destination)
        compose.runOnIdle { show.value = PodcastEntity("restored", "https://example.test/feed", "Restored show") }
        compose.onNodeWithContentDescription("View show: Restored show").performClick()
        assertEquals("podcast:restored", destination)
        compose.runOnIdle { show.value = null }
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }
}
