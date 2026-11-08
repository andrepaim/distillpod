package dev.distillpod.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.distillpod.android.features.AppViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/** Permission changes are restricted to a disposable validation app on physical phones. */
@RunWith(AndroidJUnit4::class)
class NotificationAccessDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val context get() = compose.activity
    private val packageName get() = context.packageName
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
    }
    private suspend fun await(check: () -> Boolean) = withTimeout(15000) { while (!check()) delay(50) }
    private fun enabled() = NotificationManagerCompat.from(context).areNotificationsEnabled()
    private fun guard() {
        check(Build.VERSION.SDK_INT >= 33)
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic") ||
            packageName == "dev.distillpod.android.validation.issue20")
    }
    private suspend fun setup(blocked: Boolean = false) {
        guard()
        context.applicationContext.appGraph.ready.await()
        check(androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) { "Use a fresh validation installation with notification permission initially denied" }
        shell("pm clear-permission-flags $packageName ${Manifest.permission.POST_NOTIFICATIONS} user-set user-fixed")
        if (blocked) shell("pm set-permission-flags $packageName ${Manifest.permission.POST_NOTIFICATIONS} user-set user-fixed")
        context.getSharedPreferences("notification_access", Context.MODE_PRIVATE).edit().clear().commit()
        compose.runOnUiThread {
            ViewModelProvider(context, AppViewModel.factory(context.applicationContext.appGraph))[AppViewModel::class.java].open("settings:library")
        }
        await { !enabled() }
    }
    private fun enabledText() = compose.onNodeWithText("Notifications are enabled", substring = true)
    private fun enableClick() = compose.onNodeWithText("Enable notifications").performScrollTo().performClick()
    private fun dialogButton(id: String) = device.wait(Until.findObject(By.res(Pattern.compile(".*:id/$id"))), 10000)
        ?: error("Permission dialog button $id not found")
    private fun cleanup() {
        shell("pm clear-permission-flags $packageName ${Manifest.permission.POST_NOTIFICATIONS} user-set user-fixed")
    }
    private suspend fun grantInSettingsAndReturn() {
        compose.onNodeWithText("Open notification settings").performScrollTo().performClick()
        check(device.wait(Until.hasObject(By.pkg("com.android.settings")), 10000))
        instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.POST_NOTIFICATIONS)
        await { enabled() }
        device.pressBack()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Notifications are enabled", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun denialBlockedRecoveryRuntimeGrantAndSettingsReturn() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("notificationPhase") == "verifyRevoked") {
            guard(); context.applicationContext.appGraph.ready.await()
            compose.runOnUiThread {
                ViewModelProvider(context, AppViewModel.factory(context.applicationContext.appGraph))[AppViewModel::class.java].open("settings:library")
            }
            assertFalse(enabled())
            enabledText().assertDoesNotExist()
            compose.onNodeWithText("Open notification settings").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Enable notifications").assertDoesNotExist()
            return@runBlocking
        }
        if (InstrumentationRegistry.getArguments().getString("notificationPhase") == "runtimeGrant") {
            setup(); compose.activityRule.scenario.recreate()
            enableClick(); dialogButton("permission_allow_button").click()
            await { enabled() }; enabledText().assertExists()
            compose.activityRule.scenario.recreate(); enabledText().assertExists()
            return@runBlocking
        }
        setup()
        try {
            enableClick(); dialogButton("permission_deny_button").click()
            compose.onNodeWithText("Open notification settings").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Enable notifications").assertDoesNotExist()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Enable notifications").assertDoesNotExist()

            // Reproduce a blocked older installation without the local attempt marker.
            shell("pm set-permission-flags $packageName ${Manifest.permission.POST_NOTIFICATIONS} user-set user-fixed")
            context.getSharedPreferences("notification_access", Context.MODE_PRIVATE).edit().clear().commit()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Open notification settings").performScrollTo().assertIsDisplayed()
            enableClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Enable notifications").fetchSemanticsNodes().isEmpty() }
            assertFalse(enabled())

            grantInSettingsAndReturn()
        } finally { cleanup() }
    }
}
