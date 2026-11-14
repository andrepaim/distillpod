package dev.distillpod.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.distillpod.android.features.PlayerActionDock
import dev.distillpod.android.features.PlayerShowLink
import dev.distillpod.android.features.PlayerTransport
import dev.distillpod.android.data.PodcastEntity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Now playing's dock, transport and show link keep the actions and labels of the controls they replace. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerActionDockTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()

    @Test fun distillFollowsTheCaptureRuleAndAskNeedsVoice() {
        val canDistill = mutableStateOf(false)
        val voice = mutableStateOf(false)
        compose.setContent {
            PlayerActionDock(canDistill.value, { calls += "distill" }, voice.value, voiceActive = false, onAsk = { calls += "ask" },
                onTranscript = { calls += "transcript" })
        }
        compose.onNodeWithContentDescription("Distill last 60 seconds").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Ask about this").assertDoesNotExist()
        compose.runOnIdle { canDistill.value = true; voice.value = true }
        compose.onNodeWithContentDescription("Distill last 60 seconds").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Ask about this").performClick()
        compose.onNodeWithContentDescription("Open transcript at current position").performClick()
        assertEquals(listOf("distill", "ask", "transcript"), calls)
    }

    @Test fun anActiveVoiceSessionDisablesAsk() {
        compose.setContent { PlayerActionDock(true, {}, voiceEnabled = true, voiceActive = true, onAsk = { fail() }, onTranscript = {}) }
        compose.onNodeWithContentDescription("Ask about this").assertIsNotEnabled()
    }

    @Test fun transportKeepsItsActionLabels() {
        val playing = mutableStateOf(false)
        compose.setContent {
            PlayerTransport(playing.value, { calls += "toggle" }, { calls += "skip:$it" }, speed = {}, sleep = {})
        }
        compose.onNodeWithContentDescription("Back 10 seconds").performClick()
        compose.onNodeWithContentDescription("Play").performClick()
        compose.onNodeWithContentDescription("Forward 30 seconds").performClick()
        compose.runOnIdle { playing.value = true }
        compose.onNodeWithContentDescription("Pause").assertHasClickAction()
        assertEquals(listOf("skip:-10000", "toggle", "skip:30000"), calls)
    }

    @Test fun theShowNameOpensTheShow() {
        var destination: String? = null
        compose.setContent { PlayerShowLink(PodcastEntity("selected-show", "https://example.test/feed", "Selected show")) { destination = it } }
        compose.onNodeWithText("Selected show").assertIsDisplayed()
        compose.onNodeWithContentDescription("View show: Selected show").assertHasClickAction().performClick()
        assertEquals("podcast:selected-show", destination)
    }
}
