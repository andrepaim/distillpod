package dev.distillpod.android.voice

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.distillpod.android.features.VoiceApiKeyDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceApiKeyDialogTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `key field is masked and cancel does not save draft`() {
        var saved: String? = null; var dismissed = false
        compose.setContent { VoiceApiKeyDialog({ dismissed = true }, { saved = it }) }
        compose.onNodeWithText("Save key").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .performTextInput("sk-test-only-01234567890123456789")
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed); assertNull(saved)
    }
    @Test fun `rejected key produces static error without echoing the draft`() {
        compose.setContent { VoiceApiKeyDialog({}, { throw IllegalArgumentException("private fixture detail") }) }
        compose.onNode(hasSetTextAction()).performTextInput("invalid-fixture-key")
        compose.onNodeWithText("Save key").performClick()
        compose.onNodeWithText("Enter a valid OpenAI API key").assertIsDisplayed()
        compose.onNodeWithText("private fixture detail").assertDoesNotExist()
    }
}
