package dev.distillpod.android

import android.os.Build
import android.os.SystemClock
import androidx.test.uiautomator.*
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern

/** DocumentsUI uses different list IDs across Android releases; scope rows to the locations pane. */
internal object DocumentPickerUi {
    private val pane = By.res(Pattern.compile(".*:id/(container_roots|roots_list)"))
    fun location(device: UiDevice, selector: BySelector, synchronizeApp: () -> Unit = {}) {
        val picker=By.pkg(Pattern.compile(".*documentsui"))
        val launchDeadline=SystemClock.uptimeMillis()+15000
        while(true) {
            // Background preparation may finish after an earlier idle drain. UiAutomator
            // does not advance a Compose test scheduler while awaiting the system window.
            synchronizeApp()
            if(device.hasObject(picker))break
            check(SystemClock.uptimeMillis()<launchDeadline) { failure(device,"Document picker did not open") }
            SystemClock.sleep(50)
        }
        device.waitForIdle()
        if (!device.hasObject(pane)) {
            val navigation = device.wait(Until.findObject(By.desc(Pattern.compile("Show roots|Open navigation drawer|Navigate up"))), 10000)
            checkNotNull(navigation) { failure(device, "Document picker navigation unavailable") }.click()
        }
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            val row = device.findObject(pane)?.findObject(selector)
            if (row != null) {
                val center = row.visibleCenter
                device.click(center.x, center.y)
                device.waitForIdle()
                return
            }
            SystemClock.sleep(100)
        }
        error(failure(device, "Selected document location is unavailable"))
    }
    private fun failure(device: UiDevice, message: String): String {
        // Never publish an owner's phone hierarchy from opt-in physical tests.
        val hierarchy = if (Build.PRODUCT.startsWith("sdk_") || Build.PRODUCT.contains("emulator")) runCatching {
            ByteArrayOutputStream().also(device::dumpWindowHierarchy).toString("UTF-8").take(24000)
        }.getOrDefault("Hierarchy unavailable") else "Physical-device hierarchy omitted"
        return "$message; active package=${device.currentPackageName}; $hierarchy"
    }
}
