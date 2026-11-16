package dev.distillpod.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.distillpod.android.features.AiModelChoices
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #227: Settings › ChatGPT shows an unavailable saved model and the model actually in use. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiModelChoicesTest {
    @get:Rule val compose = createComposeRule()
    private val picked = mutableListOf<String>()
    private fun show(saved: String) = compose.setContent { MaterialTheme { AiModelChoices(listOf("gpt-a", "gpt-b"), saved) { picked += it } } }
    private fun choice(model: String) = compose.onNode(hasText(model) and isSelectable())

    @Test fun `an unavailable saved model is named and the fallback in use is selected`() {
        show("retired-model")
        compose.onNodeWithText("retired-model", substring = true).assertExists()
        compose.onNodeWithText("isn't available", substring = true).assertExists()
        compose.onNodeWithText("using gpt-a", substring = true).assertExists()
        choice("gpt-a").assertIsSelected(); choice("gpt-b").assertIsNotSelected()
        choice("gpt-b").performClick()
        assertEquals(listOf("gpt-b"), picked)
    }

    @Test fun `an available saved model is selected without a warning`() {
        show("gpt-b")
        choice("gpt-b").assertIsSelected(); choice("gpt-a").assertIsNotSelected()
        compose.onNodeWithText("isn't available", substring = true).assertDoesNotExist()
    }

    @Test fun `no saved model selects the first available one`() {
        show("")
        choice("gpt-a").assertIsSelected()
        compose.onNodeWithText("isn't available", substring = true).assertDoesNotExist()
    }
}
