package dev.distillpod.android

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.distillpod.android.data.DistillationEntity
import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.data.MomentVersionEntity
import dev.distillpod.android.data.momentSnapshot
import dev.distillpod.android.features.*
import org.json.JSONObject
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
class MomentDetailLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val episode = EpisodeEntity("ep", "show", "guid", "The art of paying attention", "https://example.test/audio", mediaFingerprint = "fp")
    private val calls = mutableListOf<String>()
    private val actions = MomentActions(
        replay = { calls += "replay" }, share = { calls += "share" }, copy = { calls += "copy" },
        createPost = { calls += "post" }, openPosts = { calls += "posts" }, explain = { calls += "explain" },
        improve = { calls += "improve" }, expandContext = { calls += "expand" }, retranscribe = { calls += "retranscribe" },
        generate = { calls += "generate" }, prepare = { calls += "prepare" }, pause = { calls += "pause" },
        connectAccount = { calls += "connect" }, restore = { calls += "restore:$it" }, delete = { calls += "delete" })
    private val source = JSONObject().put("selectedStartMs", 40_000).put("selectedEndMs", 62_000).put("generationId", "g")
        .put("originalExcerpt", "Synthetic original capture text.").toString()

    private fun moment(state: String = "succeeded", quote: String? = "Make room for curiosity.", insight: String? = "A small daily practice makes a difference.",
        excerpt: String = "Attention is a practice. Make room for curiosity.", sourceJson: String = "", error: String? = null) =
        DistillationEntity("m", "ep", 40_000, 60_000, excerpt, quote, insight, fingerprint = "fp", state = state, sourceJson = sourceJson, error = error)

    private fun show(moment: DistillationEntity, versions: List<MomentVersionEntity> = emptyList(), fontScale: Float = 1f) = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            DistillTheme { MomentDetail(moment, episode, "The Listening Room", null, versions, null, actions) }
        }
    }

    private fun SemanticsNode.label() = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        ?: config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }

    @Test fun `finished moment has a header and exactly three primary actions`() {
        show(moment())
        compose.onNodeWithText("The art of paying attention").assertIsDisplayed()
        compose.onNodeWithText("The Listening Room · 0:40–1:00").assertIsDisplayed()
        val clickable = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().map { it.label() }
        assertEquals(listOf("More actions for this moment", "Replay 0:40", "Share", "Copy", "Source passage"), clickable)
        listOf("Saved", "Create post", "Posts from this moment", "Explain this idea", "Improve this distill", "Delete saved moment")
            .forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithText("Replay 0:40").performClick()
        compose.onNodeWithText("Share").performClick()
        compose.onNodeWithText("Copy").performClick()
        assertEquals(listOf("replay", "share", "copy"), calls)
    }

    @Test fun `primary actions share one row at default size`() {
        show(moment())
        val tops = listOf("Replay 0:40", "Share", "Copy").map { compose.onNodeWithText(it).getBoundsInRoot().top }
        assertEquals(1, tops.distinct().size)
    }

    @Test fun `header, content, actions and source follow reading order`() {
        show(moment())
        val top = listOf("The art of paying attention", "A small daily practice makes a difference.", "“Make room for curiosity.”", "Replay 0:40", "Source passage")
            .map { compose.onNodeWithText(it).getBoundsInRoot().top }
        assertEquals(top.sorted(), top)
    }

    @Test fun `every secondary action is one tap away`() {
        show(moment())
        compose.onNodeWithContentDescription("More actions for this moment").assertTouchWidthIsEqualTo(48.dp).assertTouchHeightIsEqualTo(48.dp).performClick()
        listOf("Create post", "Posts from this moment", "Explain this idea", "Improve this distill", "Delete saved moment")
            .forEach { compose.onNodeWithText(it, substring = true).assertIsDisplayed() }
        compose.onNodeWithText("Explain this idea").performClick()
        compose.onNodeWithContentDescription("More actions for this moment").performClick()
        compose.onNodeWithText("Posts from this moment").performClick()
        compose.onNodeWithContentDescription("More actions for this moment").performClick()
        compose.onNodeWithText("Improve this distill").performClick()
        compose.onNodeWithContentDescription("More actions for this moment").performClick()
        compose.onNodeWithText("Create post").performClick()
        assertEquals(listOf("explain", "posts", "improve", "post"), calls)
    }

    @Test fun `delete asks for confirmation`() {
        show(moment())
        compose.onNodeWithContentDescription("More actions for this moment").performClick()
        compose.onNodeWithText("Delete saved moment").performClick()
        compose.onNodeWithText("Delete this saved moment?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(calls.isEmpty())
        compose.onNodeWithContentDescription("More actions for this moment").performClick()
        compose.onNodeWithText("Delete saved moment").performClick()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(listOf("delete"), calls)
    }

    @Test fun `takeaway-only moment avoids internal wording and keeps its original capture`() {
        show(moment(quote = null, sourceJson = source))
        compose.onNodeWithText("Saved as a takeaway", substring = true).assertDoesNotExist()
        compose.onNodeWithText("The Listening Room · 0:40–1:02").assertIsDisplayed()
        compose.onNodeWithText("Synthetic original capture text.").assertDoesNotExist()
        compose.onNodeWithText("Source passage").assertHasClickAction().performScrollTo().performClick()
        compose.onNodeWithText("Attention is a practice. Make room for curiosity.").assertExists()
        compose.onNodeWithText("Synthetic original capture text.").assertExists()
        compose.onNodeWithText("The passage includes a few seconds after your tap to finish the thought.").assertExists()
    }

    @Test fun `unfinished moment explains its state and offers its recovery action`() {
        show(moment(state = "running", insight = null, quote = null))
        compose.onNodeWithText("Finding the idea").assertIsDisplayed()
        compose.onNodeWithText("Pause generation").performClick()
        compose.onNodeWithContentDescription("More actions for this moment").performClick()
        compose.onNodeWithText("Improve this distill").assertDoesNotExist()
        assertEquals(listOf("pause"), calls)
    }

    @Test fun `previous versions remain restorable`() {
        val previous = moment(insight = "An earlier takeaway.")
        show(moment(), listOf(MomentVersionEntity("v1", "m", momentSnapshot(previous))))
        compose.onNodeWithText("Previous versions (1)").performScrollTo().performClick()
        compose.onNodeWithText("An earlier takeaway.").assertExists()
        compose.onNodeWithText("Restore this version").performScrollTo().performClick()
        assertEquals(listOf("restore:v1"), calls)
    }

    @Test fun `large text keeps every primary action labelled and reachable`() {
        show(moment(), fontScale = 1.6f)
        listOf("Replay 0:40", "Share", "Copy").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithContentDescription("More actions for this moment").assertIsDisplayed()
    }
}
