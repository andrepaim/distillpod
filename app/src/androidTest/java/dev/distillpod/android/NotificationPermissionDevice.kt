package dev.distillpod.android

import android.Manifest
import android.graphics.Rect
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationManagerCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.regex.Pattern

/**
 * Android's real notification permission dialog (#20), with #67's input-readiness checks, shared by
 * Settings recovery and the in-context request (#193). Status lines stay content-free.
 */
internal class NotificationPermissionDevice(private val phase: String, private val activity: () -> ComponentActivity) {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val context get() = activity()
    private val packageName get() = context.packageName
    fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
    }
    fun granted() = androidx.core.content.ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    fun status(step: String, result: String) {
        // Android instrumentation status 2 is in-progress, never a test PASS.
        instrumentation.sendStatus(2, android.os.Bundle().apply {
            putString("stream", "Notification acceptance: phase=$phase step=$step result=$result runtimeGranted=${granted()} notificationsEnabled=${enabled()}\n")
        })
    }
    suspend fun await(step: String, timeoutMs: Long = 15000, check: () -> Boolean) {
        status(step, "waiting")
        try {
            withTimeout(timeoutMs) { while (!check()) delay(50) }
        } catch (failure: TimeoutCancellationException) {
            throw AssertionError("Notification acceptance: phase=$phase step=$step runtimeGranted=${granted()} notificationsEnabled=${enabled()} activityState=${context.lifecycle.currentState}", failure)
        }
        status(step, "passed")
    }
    suspend fun activityReturned(step: String) {
        await(step, 10000) {
            device.hasObject(By.pkg(packageName)) &&
                context.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
        }
    }
    private fun dialogDiagnostics(id: String, step: String) {
        // Deliberately omit node text, window titles, screenshots and application data.
        val button = device.findObject(By.res(Pattern.compile(".*:id/$id")))
        val windows = instrumentation.uiAutomation.windows.take(8).map { window ->
            val bounds = Rect().also(window::getBoundsInScreen)
            "type=${window.type},active=${window.isActive},focused=${window.isFocused},bounds=$bounds"
        }
        instrumentation.sendStatus(2, android.os.Bundle().apply {
            putString("stream", "Notification input: phase=$phase step=$step " +
                "buttonPresent=${button != null} enabled=${button?.isEnabled} clickable=${button?.isClickable} " +
                "bounds=${button?.visibleBounds} owner=${button?.applicationPackage} " +
                "foreground=${device.currentPackageName} windows=$windows\n")
        })
    }
    suspend fun permissionDecision(id: String) {
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
        try {
            dialogDiagnostics(id, "before-tap")
            check(button.isEnabled && button.isClickable && button.visibleBounds == target.bounds &&
                device.currentPackageName == target.owner) { "phase=$phase dialog input target changed before injection" }
            // UiObject2.click() in pinned 2.3.0 discards asynchronous injection results.
            // UiDevice.click() uses synchronous input and returns its acknowledgement.
            // One real tap only; permission/UI postconditions still establish the outcome.
            val accepted = device.click(target.bounds.centerX(), target.bounds.centerY())
            status("dialog-$id-input-acknowledged", accepted.toString())
            check(accepted) { "phase=$phase dialog-$id input injection rejected" }
            await("dialog-$id-dismissed", 10000) { !device.hasObject(selector) }
            activityReturned("dialog-$id-activity-returned")
        } catch (failure: Throwable) {
            try { dialogDiagnostics(id, "tap-failed") }
            catch (diagnosticFailure: Throwable) { failure.addSuppressed(diagnosticFailure) }
            throw failure
        }
    }
    fun enabled() = NotificationManagerCompat.from(context).areNotificationsEnabled()
}
