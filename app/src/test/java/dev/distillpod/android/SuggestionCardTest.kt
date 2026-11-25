package dev.distillpod.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.distillpod.android.data.SuggestionEntity
import dev.distillpod.android.features.SuggestionCard
import dev.distillpod.android.ui.theme.DistillTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #307: a suggested podcast card shows search's artwork placeholder and only a grounded reason. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h823dp-xxhdpi")
class SuggestionCardTest {
    @get:Rule val compose = createComposeRule()
    private val actions = mutableListOf<String>()
    private fun show(suggestion: SuggestionEntity) = compose.setContent {
        DistillTheme { SuggestionCard(suggestion, { actions += "follow" }, { actions += "dismiss" }) }
    }

    @Test fun `a grounded reason is shown beside the artwork`() {
        show(SuggestionEntity("https://example.test/talk", "Dog Talk", "Heather Parsons", "https://images.example.test/talk.jpg", "Title mentions “dog”."))
        compose.onNodeWithTag("Search artwork Dog Talk", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Title mentions “dog”.").assertIsDisplayed()
        compose.onNodeWithText("Heather Parsons").assertIsDisplayed()
        compose.onNodeWithText("Follow").performClick(); compose.onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("follow", "dismiss"), actions)
    }

    @Test fun `missing artwork shows the search placeholder and an ungrounded suggestion has no reason`() {
        show(SuggestionEntity("https://example.test/audiobooks", "Never A Dull Moment", "", null, ""))
        compose.onNodeWithTag("Search artwork Never A Dull Moment", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("NA", useUnmergedTree = true).assertIsDisplayed()
        // No blank author or reason lines are left in the card.
        compose.onAllNodes(SemanticsMatcher("blank text") { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.isBlank() } == true }, useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onNodeWithText("Never A Dull Moment").assertIsDisplayed(); compose.onNodeWithText("Follow").assertIsDisplayed()
    }
}
