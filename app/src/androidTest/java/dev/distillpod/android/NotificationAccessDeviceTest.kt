package dev.distillpod.android

import android.Manifest
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.StaleObjectException
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
    private val phase get() = InstrumentationRegistry.getArguments().getString("notificationPhase") ?: "recovery"
    private fun granted() = androidx.core.content.ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun status(step: String, result: String) {
        // Android instrumentation status 2 is in-progress, never a test PASS.
        instrumentation.sendStatus(2, android.os.Bundle().apply {
            putString("stream", "Notification acceptance: phase=$phase step=$step result=$result runtimeGranted=${granted()} notificationsEnabled=${enabled()}\n")
        })
    }
    private suspend fun await(step: String, timeoutMs: Long = 15000, check: () -> Boolean) {
        status(step, "waiting")
        try {
            withTimeout(timeoutMs) { while (!check()) delay(50) }
        } catch (failure: TimeoutCancellationException) {
            throw AssertionError("Notification acceptance: phase=$phase step=$step runtimeGranted=${granted()} notificationsEnabled=${enabled()} activityState=${context.lifecycle.currentState}", failure)
        }
        status(step, "passed")
    }
    private suspend fun activityReturned(step: String) {
        await(step, 10000) {
            device.hasObject(By.pkg(packageName)) &&
                context.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
        }
    }
    private suspend fun permissionDecision(id: String) {
        val selector = By.res(Pattern.compile(".*:id/$id"))
        data class Target(val owner: String, val bounds: Rect)
        var stableTarget: Target? = null
        var stableSince = 0L
        // Enabled/clickable alone does not establish input-window ownership or settled geometry.
        device.waitForIdle(2000)
        await("dialog-$id-ready", 10000) {
            val target = try {
                device.findObject(selector)?.let { button ->
                    val owner = button.applicationPackage
                    val bounds = button.visibleBounds
                    if (button.isEnabled && button.isClickable && owner != null && owner != packageName &&
                        !bounds.isEmpty && device.currentPackageName == owner) Target(owner, Rect(bounds)) else null
                }
            } catch (_: StaleObjectException) { null } // A replaced node is not ready for input.
            val now = SystemClock.uptimeMillis()
            if (target != stableTarget) { stableTarget = target; stableSince = now }
            target != null && now - stableSince >= 200
        }
        val target = checkNotNull(stableTarget)
        val button = checkNotNull(device.findObject(By.res(Pattern.compile(".*:id/$id")).pkg(target.owner))) {
            "phase=$phase dialog button disappeared before click"
        }
        check(button.isEnabled && button.isClickable && button.visibleBounds == target.bounds &&
            device.currentPackageName == target.owner) { "phase=$phase dialog input target changed before click" }
        // Click once only; never substitute a shell grant for the runtime UI.
        button.click()
        await("dialog-$id-dismissed", 10000) { !device.hasObject(selector) }
        activityReturned("dialog-$id-activity-returned")
    }
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
            ViewModelProvider(context, AppViewModel.factory(context.applicationContext.appGraph))[AppViewModel::class.java].open("settings:notifications")
        }
        await("setup-disabled") { !enabled() }
    }
    private fun enabledText() = compose.onNodeWithText("Notifications are enabled", substring = true)
    private fun enableClick() = compose.onNodeWithText("Enable notifications").performScrollTo().performClick()
    private fun cleanup() {
        shell("pm clear-permission-flags $packageName ${Manifest.permission.POST_NOTIFICATIONS} user-set user-fixed")
    }
    private suspend fun grantInSettingsAndReturn() {
        compose.onNodeWithText("Open notification settings").performScrollTo().performClick()
        await("settings-opened", 10000) { device.hasObject(By.pkg("com.android.settings")) }
        instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.POST_NOTIFICATIONS)
        await("settings-runtime-granted") { granted() }
        await("settings-notifications-enabled") { enabled() }
        device.pressBack()
        activityReturned("settings-activity-returned")
        await("settings-enabled-ui", 10000) { compose.onAllNodesWithText("Notifications are enabled", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun denialBlockedRecoveryRuntimeGrantAndSettingsReturn() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("notificationPhase") == "verifyRevoked") {
            guard(); context.applicationContext.appGraph.ready.await()
            compose.runOnUiThread {
                ViewModelProvider(context, AppViewModel.factory(context.applicationContext.appGraph))[AppViewModel::class.java].open("settings:notifications")
            }
            assertFalse("phase=$phase runtime permission must be revoked", granted())
            assertFalse("phase=$phase notifications must be disabled", enabled())
            status("external-revocation", "passed")
            enabledText().assertDoesNotExist()
            compose.onNodeWithText("Open notification settings").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Enable notifications").assertDoesNotExist()
            return@runBlocking
        }
        if (InstrumentationRegistry.getArguments().getString("notificationPhase") == "runtimeGrant") {
            setup(); compose.activityRule.scenario.recreate()
            activityReturned("recreated-before-runtime-grant")
            enableClick(); permissionDecision("permission_allow_button")
            await("runtime-permission-granted") { granted() }
            await("runtime-notifications-enabled") { enabled() }
            await("runtime-enabled-ui", 10000) { compose.onAllNodesWithText("Notifications are enabled", substring = true).fetchSemanticsNodes().isNotEmpty() }
            enabledText().assertExists()
            compose.activityRule.scenario.recreate(); enabledText().assertExists()
            return@runBlocking
        }
        setup()
        try {
            enableClick(); permissionDecision("permission_deny_button")
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
            // Some OEMs still show a dialog after the synthetic USER_FIXED setup.
            device.wait(Until.findObject(By.res(Pattern.compile(".*:id/permission_deny_button"))), 1500)?.click()
            activityReturned("blocked-activity-returned")
            await("blocked-enable-control-absent", 10000) { compose.onAllNodesWithText("Enable notifications").fetchSemanticsNodes().isEmpty() }
            assertFalse(enabled())

            grantInSettingsAndReturn()
        } finally { cleanup() }
    }
}
