package dev.distillpod.android

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.Preferences
import dev.distillpod.android.data.transcriptionAllowed
import dev.distillpod.android.work.TranscriptionService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptionServiceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun battery(level: Int = 80, plugged: Int = 0) {
        context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, level).putExtra(BatteryManager.EXTRA_SCALE, 100)
            .putExtra(BatteryManager.EXTRA_PLUGGED, plugged))
    }

    @Test fun `local transcription can run offline on battery but obeys charging and low battery policy`() {
        battery()
        assertTrue(transcriptionAllowed(context, Preferences(), needsNetwork = false))
        assertFalse(transcriptionAllowed(context, Preferences(chargingOnly = true), needsNetwork = false))
        battery(level = 10)
        assertFalse(transcriptionAllowed(context, Preferences(), needsNetwork = false))
        battery(level = 10, plugged = BatteryManager.BATTERY_PLUGGED_USB)
        assertTrue(transcriptionAllowed(context, Preferences(chargingOnly = true), needsNetwork = false))
    }

    @Test fun `missing audio or publisher transcript waits for a network`() {
        battery()
        shadowOf(context.getSystemService(ConnectivityManager::class.java)).setActiveNetworkInfo(null)
        assertFalse(transcriptionAllowed(context, Preferences(wifiOnly = false), needsNetwork = true))
        assertTrue(transcriptionAllowed(context, Preferences(wifiOnly = false), needsNetwork = false))
    }

    @Test fun `foreground session holds CPU awake and releases it when destroyed`() {
        val controller = Robolectric.buildService(TranscriptionService::class.java).create()
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(TranscriptionService.isRunning)
        assertNotNull(lock)
        assertTrue(lock.isHeld)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, controller.get().foregroundServiceType)
        assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
        controller.destroy()
        assertFalse(lock.isHeld)
        assertFalse(TranscriptionService.isRunning)
    }

    @Test @Config(sdk = [35]) fun `Android 15 receives media processing type without AndroidX filtering it to none`() {
        val controller = Robolectric.buildService(TranscriptionService::class.java).create()
        try {
            assertTrue(TranscriptionService.isRunning)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, controller.get().foregroundServiceType)
        } finally { controller.destroy() }
    }
}
