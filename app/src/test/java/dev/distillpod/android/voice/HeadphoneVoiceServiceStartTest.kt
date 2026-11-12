package dev.distillpod.android.voice

import android.Manifest
import android.app.Application
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.R
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeadphoneVoiceServiceStartTest {
    @Test fun `a foreground start that cannot arm still promotes before it stops`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // The service is disabled in the manifest of builds without the voice preview.
        assumeTrue(app.resources.getBoolean(R.bool.voice_enabled))
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(HeadphoneVoiceService.canArm(app))
        assertFalse(VoiceApiKeyStore(app).hasKey())
        // arm() used startForegroundService(); the key can disappear before onStartCommand.
        val controller = Robolectric.buildService(HeadphoneVoiceService::class.java).create().startCommand(0, 1)
        val service = controller.get()
        assertNotNull("Android crashes a foreground start stopped before startForeground()",
            shadowOf(service).lastForegroundNotification)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, service.foregroundServiceType)
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller.destroy()
    }
}
