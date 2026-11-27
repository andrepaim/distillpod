package dev.distillpod.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.distillpod.android.SocialFixtures.CAPTION
import dev.distillpod.android.SocialFixtures.CARD
import dev.distillpod.android.SocialFixtures.SOURCE
import dev.distillpod.android.SocialFixtures.cardArtifact
import dev.distillpod.android.data.*
import dev.distillpod.android.features.*
import dev.distillpod.android.sharing.PostAssets
import dev.distillpod.android.ui.theme.DistillTheme
import dev.distillpod.android.voice.VoiceState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #324: every stateless surface that shows model-written text offers Report this result, reachable by TalkBack. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h823dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReportResultSurfacesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** The action opens the confirmation that shows [expected] before anything is sent. */
    private fun reportOpensConfirmation(expected: String, scroll: Boolean = true) {
        val action = compose.onNode(hasText("Report this result") and hasClickAction())
        (if (scroll) action.performScrollTo() else action).assertIsDisplayed().performClick()
        compose.onNodeWithText("Open email app").assertIsDisplayed()
        compose.onNode(hasText(expected, substring = true) and hasAnyAncestor(isDialog())).assertExists()
    }

    @Test fun `a saved moment's More actions menu reports its takeaway and quote`() {
        val episode = EpisodeEntity("ep", "show", "guid", "Episode", "https://example.test/audio", mediaFingerprint = "fp")
        val moment = DistillationEntity("m", "ep", 40_000, 60_000, "Passage text.", "Make room for curiosity.", "Curiosity needs space.",
            fingerprint = "fp", state = "succeeded", model = "gpt-5.1")
        compose.setContent { DistillTheme { MomentDetail(moment, episode, "Show", null, emptyList(), null, MomentActions()) } }
        compose.onNodeWithText("Report this result").assertDoesNotExist()
        compose.onNodeWithContentDescription("More actions for this moment").performClick()
        reportOpensConfirmation("Curiosity needs space.")
        compose.onNode(hasText("Model: gpt-5.1", substring = true) and hasAnyAncestor(isDialog())).assertExists()
    }

    @Test fun `a moment without a result has nothing to report`() {
        val moment = DistillationEntity("m", "ep", 40_000, 60_000, "Passage text.", state = "waiting_transcript")
        compose.setContent { DistillTheme { MomentDetail(moment, null, "Show", null, emptyList(), null, MomentActions()) } }
        compose.onNodeWithContentDescription("More actions for this moment").performClick()
        compose.onNodeWithText("Report this result").assertDoesNotExist()
    }

    @Test fun `a share post's menu reports the post`() {
        val result = SocialResult(SOURCE, SocialResult.CARD, "ready", artifact = cardArtifact(CARD, CAPTION))
        val assets = PostAssets(java.nio.file.Files.createTempDirectory("report-post").toFile())
        compose.setContent { DistillTheme { Surface(color = MaterialTheme.colorScheme.background) { PostResult(result, assets, PostActions()) } } }
        compose.onNodeWithContentDescription("More post actions").performClick()
        reportOpensConfirmation(CAPTION.lines().first())
    }

    @Test fun `a podcast suggestion can be reported`() {
        compose.setContent { DistillTheme { SuggestionCard(SuggestionEntity("https://example.test/talk", "Dog Talk", "Heather Parsons", null, "Title mentions “dog”."), {}, {}) } }
        reportOpensConfirmation("Title mentions “dog”.", scroll = false)
    }

    @Test fun `the voice preview's last answer can be reported, not one still being spoken`() {
        compose.setContent { VoiceSessionContent(VoiceState("Answering", "Partial words"), true, {}, {}, {}) }
        compose.onNodeWithText("Report this result").assertDoesNotExist()
    }

    @Test fun `the voice preview's finished answer offers the report`() {
        compose.setContent { DistillTheme { VoiceSessionContent(VoiceState(answer = "Rest comes first."), false, {}, {}, {}) } }
        reportOpensConfirmation("Rest comes first.")
    }
}
