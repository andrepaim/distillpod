package dev.distillpod.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.distillpod.android.features.InsightsPreview
import dev.distillpod.android.features.LearnMoreLink
import dev.distillpod.android.features.ShowFollowButton
import dev.distillpod.android.features.settingsSyncSummary
import dev.distillpod.android.features.transcriptSettingSummary
import dev.distillpod.android.sync.SyncBindingEntity
import dev.distillpod.android.ui.theme.DistillTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #203 phase 3a: short status values, Learn more sheets, the Following button and the insights preview. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmberSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()
    private val now = 1_800_000_000_000L
    private fun binding(status: String = "synced", enabled: Boolean = true, at: Long = 0) =
        SyncBindingEntity(uid = "u", generation = "g", enabled = enabled, lease = "", status = status, lastSyncedAt = at)

    @Test fun `sync summary is a short state read from the binding`() {
        assertEquals("Unavailable", settingsSyncSummary(false, null, false, "", 0, now))
        assertEquals("Off", settingsSyncSummary(true, null, false, "", 0, now))
        assertEquals("Sign in", settingsSyncSummary(true, binding(), false, "", 0, now))
        assertEquals("Sign in", settingsSyncSummary(true, binding(), true, "other", 0, now))
        assertEquals("Paused", settingsSyncSummary(true, binding(enabled = false), true, "u", 0, now))
        assertEquals("Synced", settingsSyncSummary(true, binding(), true, "u", 0, now))
        assertEquals("Synced just now", settingsSyncSummary(true, binding(at = now - 30_000), true, "u", 0, now))
        assertEquals("Synced 2 min ago", settingsSyncSummary(true, binding(at = now - 120_000), true, "u", 0, now))
        assertEquals("Synced 3 h ago", settingsSyncSummary(true, binding(at = now - 3 * 3_600_000L), true, "u", 0, now))
        assertEquals("Synced 2 d ago", settingsSyncSummary(true, binding(at = now - 2 * 86_400_000L), true, "u", 0, now))
        assertEquals("Synced", settingsSyncSummary(true, binding(at = now + 60_000), true, "u", 0, now))
        assertEquals("4 waiting", settingsSyncSummary(true, binding(), true, "u", 4, now))
        assertEquals("Syncing…", settingsSyncSummary(true, binding("syncing"), true, "u", 0, now))
        assertEquals("Offline", settingsSyncSummary(true, binding("offline"), true, "u", 0, now))
        for (status in listOf("conflict", "rejected", "error", "upgrade_required"))
            assertEquals("Needs attention", settingsSyncSummary(true, binding(status), true, "u", 0, now))
    }

    @Test fun `transcript summary names the model and automatic preparation`() {
        assertEquals("Tiny · auto", transcriptSettingSummary("tiny", true))
        assertEquals("Base · manual", transcriptSettingSummary("base", false))
        assertEquals("manual", transcriptSettingSummary("unknown", false))
    }

    @Test fun `learn more keeps the full text in a sheet`() {
        val full = listOf("First full sentence. Second sentence.", "Another disclosure paragraph.")
        compose.setContent { DistillTheme { LearnMoreLink("AI data use", full) } }
        compose.onNodeWithText("Another disclosure paragraph.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Learn more about AI data use").assertHasClickAction().performClick()
        compose.onNodeWithText("First full sentence. Second sentence.").assertIsDisplayed()
        compose.onNodeWithText("Another disclosure paragraph.").assertIsDisplayed()
    }

    @Test fun `following opens a menu whose unsubscribe is confirmed`() {
        compose.setContent { DistillTheme { ShowFollowButton("Synthetic Show", true, { calls += "follow" }, { calls += "unsubscribe" }) } }
        compose.onNodeWithText("Following").assertIsDisplayed().performClick()
        compose.onNodeWithText("Unsubscribe").performClick()
        assertEquals(emptyList<String>(), calls)
        compose.onNodeWithText("Unsubscribe from Synthetic Show?").assertIsDisplayed()
        compose.onNodeWithText("It stops refreshing on your devices. Downloaded episodes, listening progress and saved moments are kept.").assertIsDisplayed()
        compose.onNode(hasText("Unsubscribe") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf("unsubscribe"), calls)
    }

    @Test fun `an unfollowed show offers Follow directly`() {
        compose.setContent { DistillTheme { ShowFollowButton("Synthetic Show", false, { calls += "follow" }, { calls += "unsubscribe" }) } }
        compose.onNodeWithText("Following").assertDoesNotExist()
        compose.onNodeWithText("Follow").performClick()
        assertEquals(listOf("follow"), calls)
    }

    @Test fun `insights preview lists only what the analysis produces`() {
        compose.setContent { DistillTheme { InsightsPreview(durationMs = 40 * 60_000L) } }
        for (item in listOf("Summary", "Chapters", "Ad markers")) compose.onNodeWithText(item).assertIsDisplayed()
        compose.onNodeWithText("Key quotes").assertDoesNotExist()
        compose.onNodeWithText("From an episode of about 40 min", substring = true).assertIsDisplayed()
    }
}
