package dev.distillpod.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.distillpod.android.data.AvailableUpdate
import dev.distillpod.android.data.UpdateState
import dev.distillpod.android.features.AppUpdatesSection
import dev.distillpod.android.features.SettingsOverview
import dev.distillpod.android.features.SettingsStatus
import dev.distillpod.android.features.lastChecked
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdatesScreenTest {
    @get:Rule val compose = createComposeRule()
    private val opened = mutableListOf<String>()
    private val toggles = mutableListOf<Boolean>()
    private var checks = 0
    private val now = 1_800_000_000_000L
    private val update = AvailableUpdate("0.29.0-alpha.1", 53, "https://github.com/andrepaim/distillpod/releases/tag/v0.29.0-alpha.1",
        "https://github.com/andrepaim/distillpod/releases/download/v0.29.0-alpha.1/distillpod-0.29.0-alpha.1.apk", "Share cards get better.")

    private fun show(state: UpdateState, checking: Boolean = false, fontScale: Float = 1f) = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            MaterialTheme { Column(Modifier.width(360.dp)) {
                AppUpdatesSection(state, checking, now, { toggles += it }, { checks++ }) { opened += it }
            } }
        }
    }

    @Test fun `a newer release offers its APK download and notes`() {
        show(UpdateState(available = update, checkedAt = now - 3_600_000), fontScale = 1.6f)
        compose.onNodeWithText("DistillPod 0.29.0-alpha.1 is available").assertIsDisplayed()
        compose.onNodeWithText("Share cards get better.").assertIsDisplayed()
        compose.onNodeWithText("Download APK").assertHasClickAction().performClick()
        compose.onNodeWithText("What's new").performClick()
        assertEquals(listOf(update.apkUrl, update.pageUrl), opened)
        compose.onNodeWithText("Last checked 1 hour ago").assertExists()
    }

    @Test fun `without an update only the switch and the manual check show`() {
        show(UpdateState(enabled = true, checkedAt = 0))
        compose.onNodeWithText("Download APK").assertDoesNotExist()
        compose.onNodeWithText("Not checked yet").assertExists()
        compose.onNodeWithContentDescription("Check for updates").assertIsOn().performClick()
        compose.onNodeWithText("Check now").performClick()
        assertEquals(listOf(false), toggles); assertEquals(1, checks)
    }

    @Test fun `a running check cannot be started twice`() {
        show(UpdateState(enabled = false), checking = true)
        compose.onNodeWithContentDescription("Check for updates").assertIsOff()
        compose.onNodeWithText("Checking…").assertIsNotEnabled()
    }

    @Test fun `last checked reads naturally`() {
        assertEquals("Not checked yet", lastChecked(0, now))
        assertEquals("Last checked just now", lastChecked(now - 20_000, now))
    }

    @Test fun `settings overview marks About when an update is available`() {
        compose.setContent { MaterialTheme { SettingsOverview(false, false, SettingsStatus(version = "0.28.0-alpha.1", update = "0.29.0-alpha.1")) {} } }
        compose.onNode(hasText("About DistillPod") and hasText("Update available") and hasClickAction()).performScrollTo().assertIsDisplayed()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateNoticeTest {
    private val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    private val manager = context.getSystemService(android.app.NotificationManager::class.java)
    private val update = AvailableUpdate("0.29.0-alpha.1", 53, "https://github.com/andrepaim/distillpod/releases/tag/v0.29.0-alpha.1",
        "https://github.com/andrepaim/distillpod/releases/download/v0.29.0-alpha.1/distillpod-0.29.0-alpha.1.apk", "Share cards get better.")

    @Test fun `the notice names the version, opens About and offers the APK`() {
        org.robolectric.Shadows.shadowOf(manager).setNotificationsEnabled(true)
        dev.distillpod.android.work.UpdateNotice.post(context, update)
        val posted = org.robolectric.Shadows.shadowOf(manager).allNotifications.single()
        assertEquals("DistillPod 0.29.0-alpha.1 is available", posted.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals("Share cards get better.", posted.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        assertEquals(dev.distillpod.android.work.UpdateNotice.CHANNEL, posted.channelId)
        val open = org.robolectric.Shadows.shadowOf(posted.contentIntent).savedIntent
        assertEquals(true, open.getBooleanExtra(dev.distillpod.android.work.UpdateNotice.OPEN_ABOUT, false))
        val download = org.robolectric.Shadows.shadowOf(posted.actions.single { it.title == "Download APK" }.actionIntent).savedIntent
        assertEquals(update.apkUrl, download.dataString)
    }

    @Test fun `nothing is posted while notifications are off`() {
        org.robolectric.Shadows.shadowOf(manager).setNotificationsEnabled(false)
        dev.distillpod.android.work.UpdateNotice.post(context, update)
        assertEquals(0, org.robolectric.Shadows.shadowOf(manager).allNotifications.size)
    }
}
