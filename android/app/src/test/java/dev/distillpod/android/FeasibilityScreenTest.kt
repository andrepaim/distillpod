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
        compose.onNodeWithText("Import audio clip").performScrollTo().assertIsDisplayed()
    }
    @Test fun `busy execution prevents starting duplicate jobs`() {
        compose.setContent { FeasibilityScreen("Transcribing", true, true, "", {}, {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("Transcribe clip").assertIsNotEnabled()
        compose.onNodeWithText("Run AI check").assertIsNotEnabled()
    }
    @Test fun `cancellable local work exposes cancellation while keeping duplicate work disabled`() {
        var cancelled = false
        compose.setContent { FeasibilityScreen("Transcribing", true, true, "", {}, {}, {}, {}, {}, {}, {},
            cancellable = true, onCancel = { cancelled = true }) }
        compose.onNodeWithText("Cancel operation").performScrollTo().performClick()
        assertTrue(cancelled)
        compose.onNodeWithText("Transcribe clip").assertIsNotEnabled()
    }
    @Test fun `a recoverable account failure offers an explicit retry`() {
        var retried = false
        compose.setContent { FeasibilityScreen("Check your network", false, false, "", {}, {}, {}, {}, {}, {}, {},
            retryConnection = true, onRetryConnection = { retried = true }) }
        compose.onNodeWithText("Retry account connection").performClick()
        assertTrue(retried)
        compose.onNodeWithText("Run AI check").assertIsNotEnabled()
    }
}
