package dev.distillpod.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.distillpod.android.features.SettingsOverview
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #188: the overview starts with its destinations, groups are headings and About is an ordinary row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsOverviewTest {
    @get:Rule val compose = createComposeRule()
    private val opened = mutableListOf<String>()

    private fun show(voiceEnabled: Boolean = false, connected: Boolean = false, fontScale: Float = 1f) = compose.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            MaterialTheme { Box(Modifier.width(360.dp)) { SettingsOverview(connected, voiceEnabled) { opened += it } } }
        }
    }

    @Test fun `marketing header is gone and the first group starts the page`() {
        show()
        compose.onNodeWithText("Make it yours").assertDoesNotExist()
        compose.onNodeWithText("Your library, your listening, your choices.").assertDoesNotExist()
        val headings = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].joinToString { it.text } }
        assertEquals(listOf("Your library", "Playback & transcripts", "App access & privacy"), headings)
        compose.onNodeWithText("Backup & Restore").assertIsDisplayed()
    }

    @Test fun `every destination keeps its route and About is a regular row`() {
        show(voiceEnabled = true)
        val rows = listOf("Backup & Restore" to "backup", "Library sync" to "settings:sync",
            "Library" to "settings:library", "Playback & headphones" to "settings:playback", "Voice" to "settings:voice",
            "Transcript preparation" to "settings:transcription", "ChatGPT" to "settings:ai",
            "Downloads & storage" to "settings:storage", "Notification access" to "settings:notifications",
            "Privacy & diagnostics" to "settings:diagnostics", "About DistillPod" to "settings:about")
        for ((title, _) in rows) compose.onNode(hasText(title) and hasClickAction()).performScrollTo().performClick()
        assertEquals(rows.map { it.second }, opened)
        compose.onNode(hasText("About DistillPod") and hasText("Version, licenses and source code") and hasClickAction())
            .performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test fun `voice row follows the build flag and ChatGPT reflects the connection`() {
        show(voiceEnabled = false, connected = true)
        compose.onNodeWithText("Voice").assertDoesNotExist()
        compose.onNodeWithText("Connected · account and AI model").performScrollTo().assertIsDisplayed()
    }

    @Test fun `rows stay reachable at two hundred percent font size`() {
        show(fontScale = 2f)
        compose.onNodeWithText("Backup & Restore").assertIsDisplayed()
        compose.onNode(hasText("About DistillPod") and hasClickAction()).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("settings:about"), opened)
    }
}
