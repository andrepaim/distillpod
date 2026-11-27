package dev.distillpod.android

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.features.AiResultReport
import dev.distillpod.android.features.ReportEmail
import dev.distillpod.android.features.ReportResultDialog
import dev.distillpod.android.ui.theme.DistillTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** #324: the user sees the exact message, can remove the result, and nothing leaves the phone without their email app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReportResultDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val application get() = ApplicationProvider.getApplicationContext<Application>()
    private val report = AiResultReport("Ask this episode", "The guest says rest matters more than effort.", "gpt-5.1")
    private var open = true

    private fun show() = compose.setContent {
        var visible by remember { mutableStateOf(true) }
        DistillTheme { if (visible) ReportResultDialog(report, onDismiss = { visible = false; open = false }, appVersion = "0.38.1-alpha.1") }
    }
    private fun started() = shadowOf(compose.activity).nextStartedActivity ?: shadowOf(application).nextStartedActivity

    @Test fun `the exact message is shown before anything opens`() {
        show()
        compose.onNodeWithText("Report this result").assertIsDisplayed()
        compose.onNodeWithText(report.body("0.38.1-alpha.1", true)).assertExists()
        compose.onNodeWithText("To: $ReportEmail", substring = true).assertExists()
        compose.onNodeWithText("Subject: ${report.subject()}").assertExists()
        assertNull(started())
    }

    @Test fun `cancel sends nothing`() {
        show()
        compose.onNodeWithText("Cancel").performClick()
        assertNull(started())
        assertFalse(open)
    }

    @Test fun `opening the email app carries the shown message`() {
        show()
        compose.onNodeWithText("Open email app").performClick()
        val intent = started()!!
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals(report.body("0.38.1-alpha.1", true), intent.getStringExtra(Intent.EXTRA_TEXT))
        assertFalse(open)
    }

    @Test fun `the result text can be removed before the email app opens`() {
        show()
        compose.onNode(hasText("Include the result text") and isToggleable()).assertIsOn().performScrollTo().performClick().assertIsOff()
        compose.onNodeWithText(report.body("0.38.1-alpha.1", false)).assertExists()
        compose.onNodeWithText(report.result, substring = true).assertDoesNotExist()
        compose.onNodeWithText("Open email app").performClick()
        val text = started()!!.getStringExtra(Intent.EXTRA_TEXT)!!
        assertEquals(report.body("0.38.1-alpha.1", false), text)
        assertFalse(text.contains(report.result))
    }

    @Test fun `without an email app the address and text are shown to copy`() {
        shadowOf(application).checkActivities(true)
        show()
        compose.onNodeWithText("Open email app").performClick()
        assertNull(started())
        compose.onNodeWithText("No email app found").assertIsDisplayed()
        compose.onNodeWithText(ReportEmail).assertExists()
        compose.onNodeWithText(report.result, substring = true).assertExists()
        compose.onNodeWithText("Copy report").performClick()
        val clip = application.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        assertTrue(clip.startsWith("To: $ReportEmail\nSubject: ${report.subject()}\n\n"))
        assertTrue(clip.endsWith(report.body("0.38.1-alpha.1", true)))
        assertFalse(open)
    }
}
