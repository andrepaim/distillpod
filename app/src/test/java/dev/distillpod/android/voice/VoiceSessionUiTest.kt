package dev.distillpod.android.voice

import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import dev.distillpod.android.features.VoiceSessionContent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceSessionUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `listening shows actual microphone level and Done`() {
        var done = false
        compose.setContent { VoiceSessionContent(VoiceState("Listening", level = .4f), true, { done = true }, {}, {}) }
        compose.onNodeWithText("Listening…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Microphone input level").assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(.4f, 0f..1f))
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(400))
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("Done").performClick()
        assertTrue(done)
    }
    @Test fun `answer transcript stays within bounded scroll area and microphone is off`() {
        val answer = "A longer spoken explanation. ".repeat(200)
        compose.setContent { VoiceSessionContent(VoiceState("Answering", answer), true, {}, {}, {}) }
        compose.onNodeWithText("Microphone off · Spoken answer").assertIsDisplayed()
        compose.onNodeWithContentDescription("Microphone input level").assertDoesNotExist()
        val bounds = compose.onNodeWithContentDescription("Spoken answer transcript").getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top <= 220.dp)
        compose.onNodeWithText("Stop and resume").assertIsDisplayed()
    }
    @Test fun `last answer is review only and retry is confined to failed interaction`() {
        compose.setContent { VoiceSessionContent(VoiceState(answer = "Previous answer"), false, {}, {}, {}) }
        compose.onNodeWithText("Last answer").assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Done").assertDoesNotExist()
    }
}
