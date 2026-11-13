package dev.distillpod.android

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.features.NotificationControls
import dev.distillpod.android.features.NotificationPrompt
import dev.distillpod.android.features.NotificationPromptHost
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** In-context notification request (#193). Standalone: no application graph, WorkManager or playback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationPromptTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val application get() = ApplicationProvider.getApplicationContext<Application>()
    private val permissionPrefs get() = application.getSharedPreferences("notification_access", Context.MODE_PRIVATE)
    private var settingsOpened = 0

    @Before fun setup() {
        permissionPrefs.edit().clear().commit()
        notifications(false)
    }

    private fun notifications(enabled: Boolean) {
        shadowOf(application.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(enabled)
        if (Build.VERSION.SDK_INT >= 33) {
            if (enabled) shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            else shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    private fun render(prompt: NotificationPrompt = NotificationPrompt(application)): NotificationPrompt {
        compose.setContent {
            MaterialTheme {
                val snackbar = remember { SnackbarHostState() }
                Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                    Box(Modifier.padding(padding)) { NotificationPromptHost(prompt, snackbar) { settingsOpened++ } }
                }
            }
        }
        return prompt
    }
    private fun explanation() = compose.onNodeWithText("Show progress outside the app?")
    private fun requestResult(granted: Boolean) {
        val request = shadowOf(compose.activity).lastRequestedPermission ?: error("No runtime permission request")
        assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions)
        compose.runOnIdle {
            compose.activity.onRequestPermissionsResult(request.requestCode, arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                intArrayOf(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED))
        }
        compose.waitForIdle()
    }
    private fun startWork(prompt: NotificationPrompt) { compose.runOnIdle { prompt.workStarted() }; compose.waitForIdle() }

    @Test fun `nothing is shown before work starts`() {
        render()
        explanation().assertDoesNotExist()
        assertNull(shadowOf(compose.activity).lastRequestedPermission)
    }

    @Test fun `first started work explains before the system request and a grant needs no link`() {
        val prompt = render()
        startWork(prompt)
        explanation().assertExists()
        compose.onNodeWithText("downloads and transcript preparation", substring = true).assertExists()
        // The explanation comes first; Android's dialog only after the user chooses to allow.
        assertNull(shadowOf(compose.activity).lastRequestedPermission)
        compose.onNodeWithText("Allow notifications").performClick()
        explanation().assertDoesNotExist()
        // Shares #20's attempt marker, so Settings uses its recovery path afterwards.
        assertTrue(permissionPrefs.getBoolean("runtime_requested", false))
        notifications(true); requestResult(true)
        compose.onNodeWithText("Notifications are off", substring = true).assertDoesNotExist()
        startWork(prompt)
        explanation().assertDoesNotExist()
    }

    @Test fun `denial is not repeated and leaves a quiet settings link`() {
        val prompt = render()
        startWork(prompt)
        compose.onNodeWithText("Allow notifications").performClick()
        requestResult(false)
        compose.onNodeWithText("Notifications are off", substring = true).assertExists()
        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
        assertEquals(1, settingsOpened)
        startWork(prompt)
        explanation().assertDoesNotExist()
        assertTrue(permissionPrefs.getBoolean("runtime_requested", false))
    }

    @Test fun `not now asks nothing of Android and is not offered again even after restart`() {
        val prompt = render()
        startWork(prompt)
        compose.onNodeWithText("Not now").performClick()
        explanation().assertDoesNotExist()
        assertNull(shadowOf(compose.activity).lastRequestedPermission)
        compose.onNodeWithText("Notifications are off", substring = true).assertExists()
        // The one platform request stays available in Settings → Notification access.
        assertFalse(permissionPrefs.getBoolean("runtime_requested", false))
        startWork(prompt)
        explanation().assertDoesNotExist()
        val restarted = NotificationPrompt(application)
        restarted.workStarted()
        assertFalse(restarted.offered.value)
    }

    @Test fun `dismissing the explanation counts as not now`() {
        val prompt = render()
        startWork(prompt)
        // Back on the explanation's own window, as the system delivers it.
        @Suppress("DEPRECATION")
        compose.runOnUiThread { org.robolectric.shadows.ShadowDialog.getLatestDialog().onBackPressed() }
        compose.waitForIdle()
        explanation().assertDoesNotExist()
        assertNull(shadowOf(compose.activity).lastRequestedPermission)
        startWork(prompt)
        explanation().assertDoesNotExist()
    }

    @Test fun `repeated starts while the explanation is open show it once`() {
        val prompt = render()
        startWork(prompt); startWork(prompt)
        compose.onAllNodesWithText("Show progress outside the app?").assertCountEquals(1)
    }

    @Test fun `not offered when notifications are already allowed`() {
        notifications(true)
        val prompt = render()
        startWork(prompt)
        explanation().assertDoesNotExist()
        assertFalse(prompt.offered.value)
    }

    @Test fun `not offered after the settings runtime request was used`() {
        permissionPrefs.edit().putBoolean("runtime_requested", true).commit()
        val prompt = render()
        startWork(prompt)
        explanation().assertDoesNotExist()
    }

    @Test fun `settings runtime request is replaced by recovery after an in-context request`() {
        val prompt = NotificationPrompt(application)
        prompt.workStarted()
        prompt.requesting()
        compose.setContent { MaterialTheme { NotificationControls() } }
        compose.onNodeWithText("Enable notifications").assertDoesNotExist()
        compose.onNodeWithText("Open notification settings").assertExists()
    }

    @Test @Config(sdk = [32]) fun `not offered before Android 13 where there is no runtime request`() {
        val prompt = render()
        startWork(prompt)
        explanation().assertDoesNotExist()
        assertFalse(prompt.offered.value)
    }
}
