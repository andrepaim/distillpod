package dev.distillpod.android

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.features.AppViewModel
import dev.distillpod.android.features.DataControls
import dev.distillpod.android.features.NotificationControls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationAccessTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val application get() = ApplicationProvider.getApplicationContext<Application>()
    private val permissionPrefs get() = application.getSharedPreferences("notification_access", Context.MODE_PRIVATE)

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
    private fun render() = compose.setContent {
        MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { NotificationControls() } }
    }
    private fun enabledText() = compose.onNodeWithText("Notifications are enabled", substring = true)
    private fun resume() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }
    private fun requestResult(granted: Boolean) {
        val request = shadowOf(compose.activity).lastRequestedPermission ?: error("No runtime permission request")
        compose.runOnIdle {
            compose.activity.onRequestPermissionsResult(request.requestCode, arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                intArrayOf(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED))
        }
    }

    @Test fun `library import controls no longer duplicate notification access`() {
        // Only this case needs a library ViewModel. Do not start app recovery or bind playback.
        shadowOf(application).declareComponentUnbindable(ComponentName(application, PlaybackService::class.java))
        val scope = CoroutineScope(Dispatchers.Main.immediate + Job().apply { cancel() })
        val graph = AppGraph(application, scope)
        val store = ViewModelStore()
        try {
            val vm = AppViewModel(graph, SavedStateHandle())
            store.put("settings", vm)
            compose.setContent {
                MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { DataControls(vm) } }
            }
            compose.onNodeWithText("Import OPML").assertExists()
            compose.onNodeWithText("Background progress").assertDoesNotExist()
            compose.onNodeWithText("Enable notifications").assertDoesNotExist()
            compose.onNodeWithText("Open notification settings").assertDoesNotExist()
        } finally {
            store.clear()
            shadowOf(Looper.getMainLooper()).idle()
            graph.db.close()
        }
    }

    @Test fun `an existing notification grant is reflected initially`() {
        notifications(true); render()
        enabledText().assertExists()
        compose.onNodeWithText("Enable notifications").assertDoesNotExist()
    }

    @Test fun `initial runtime grant refreshes actual enabled state`() {
        render()
        compose.onNodeWithText("Enable notifications").performScrollTo().performClick()
        assertNotNull(shadowOf(compose.activity).lastRequestedPermission)
        notifications(true); requestResult(true)
        enabledText().assertExists()
    }

    @Test fun `denial leaves a clear settings action instead of another no-op prompt`() {
        render()
        compose.onNodeWithText("Enable notifications").performScrollTo().performClick()
        requestResult(false)
        compose.onNodeWithText("Open notification settings").performScrollTo().performClick()
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(compose.activity.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertTrue(permissionPrefs.getBoolean("runtime_requested", false))
        compose.onNodeWithText("Enable notifications").assertDoesNotExist()
    }

    @Test fun `already attempted or blocked permission goes directly to notification settings`() {
        permissionPrefs.edit().putBoolean("runtime_requested", true).commit()
        render()
        compose.onNodeWithText("Open notification settings").performScrollTo().performClick()
        assertNull(shadowOf(compose.activity).lastRequestedPermission)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, shadowOf(compose.activity).nextStartedActivity.action)
    }

    @Test fun `retained screen refreshes external grant and revocation on resume`() {
        render(); enabledText().assertDoesNotExist()
        notifications(true); resume(); enabledText().assertExists()
        notifications(false); resume(); enabledText().assertDoesNotExist()
        compose.onNodeWithText("Open notification settings").assertExists()
    }

    @Test @Config(sdk = [32]) fun `legacy Android notification settings return refreshes the retained screen`() {
        render()
        compose.onNode(hasText("Enable notifications") or hasText("Open notification settings")).performScrollTo().performClick()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, shadowOf(compose.activity).nextStartedActivity.action)
        notifications(true); resume(); enabledText().assertExists()
        notifications(false); resume(); enabledText().assertDoesNotExist()
    }
}
