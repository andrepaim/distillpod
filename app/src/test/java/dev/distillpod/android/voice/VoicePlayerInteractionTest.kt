package dev.distillpod.android.voice

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import dev.distillpod.android.features.VoicePlayerInteraction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoicePlayerInteractionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `completion dismisses transcript and last answer reopens only on request`() {
        var state by mutableStateOf(VoiceState("Answering", answer = "A concise explanation"))
        compose.setContent { VoicePlayerInteraction(state, { true }, {}, {}) }
        compose.onNodeWithContentDescription("Spoken answer transcript").assertExists()
        compose.runOnIdle { state = state.copy(phase = "Idle") }
        compose.mainClock.advanceTimeBy(1200)
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Spoken answer transcript").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Spoken answer transcript").assertDoesNotExist()
        compose.onNodeWithText("View last answer").performClick()
        compose.onNodeWithText("Last answer").assertIsDisplayed()
        compose.onNodeWithContentDescription("Spoken answer transcript").assertExists()
    }
    @Test fun `failure remains inside sheet until dismissed and does not stick on player`() {
        var state by mutableStateOf(VoiceState("Preparing"))
        var cancellations = 0
        compose.setContent { VoicePlayerInteraction(state, { true }, {}, { cancellations++ }) }
        compose.runOnIdle { state = VoiceState(error = "Fixture connection failed") }
        compose.onNodeWithText("Try again").assertExists()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Return to podcast").performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(1, cancellations) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Fixture connection failed").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Fixture connection failed").assertDoesNotExist()
        compose.onNodeWithText("Ask about this").assertIsDisplayed()
    }
}
