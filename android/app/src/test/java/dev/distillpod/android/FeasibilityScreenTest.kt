package dev.distillpod.android
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeasibilityScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `account and transcription actions expose clear status`() {
        var connected = false
        compose.setContent { FeasibilityScreen("Ready for validation", false, false, "", {}, { connected = true }, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("Continue with ChatGPT").performClick()
        assertTrue(connected)
        compose.onNodeWithText("Ready for validation").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Import Whisper model").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Import WAV clip").performScrollTo().assertIsDisplayed()
    }
    @Test fun `busy execution prevents starting duplicate jobs`() {
        compose.setContent { FeasibilityScreen("Transcribing", true, true, "", {}, {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("Transcribe clip").assertIsNotEnabled()
        compose.onNodeWithText("Run AI check").assertIsNotEnabled()
    }
}
