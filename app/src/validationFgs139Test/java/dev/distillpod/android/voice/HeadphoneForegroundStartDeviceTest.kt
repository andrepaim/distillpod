package dev.distillpod.android.voice

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.MainActivity
import dev.distillpod.android.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in #139 probe for the isolated fgs139 package only. A real foreground-service start whose
 * service cannot arm (no voice key) must promote before stopping; otherwise Android 12+ kills the
 * process with ForegroundServiceDidNotStartInTimeException. No key, account or network is used.
 */
@RunWith(AndroidJUnit4::class)
class HeadphoneForegroundStartDeviceTest {
    @Test fun foregroundStartWithoutKeyPromotesThenStopsWithoutCrashing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "dev.distillpod.android.validation.fgs139")
        check(context.resources.getBoolean(R.bool.voice_enabled))
        check(!VoiceApiKeyStore(context).hasKey()) { "The isolated package must not hold a voice key" }
        for (permission in listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        assertTrue(HeadphoneVoiceService.canArm(context))
        // A visible activity makes the start eligible, as arm() is in production. Let its
        // readiness collector settle first: it stops (not starts) the service while voice is off.
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            Thread.sleep(3000)
            ContextCompat.startForegroundService(context, Intent(context, HeadphoneVoiceService::class.java))
            // Android's immediate crash for a stop-before-promote arrives within milliseconds;
            // the start timeout itself is 30 s. Wait past both a stop and a tight timeout margin.
            Thread.sleep(12000)
            @Suppress("DEPRECATION")
            val running = context.getSystemService(ActivityManager::class.java).getRunningServices(50)
                .any { it.service.className == HeadphoneVoiceService::class.java.name }
            assertFalse("The no-key start must stop after promotion", running)
        } finally { activity.finish() }
    }
}
