package dev.distillpod.android.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import io.sentry.Sentry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ForegroundServiceFailureTest {
    private fun record(cls:String, pkg:String="dev.distillpod.android") =
        "Context.startForegroundService() did not then call Service.startForeground(): ServiceRecord{9f3c2b1 u0 $pkg/$cls c:$pkg}"

    @Test fun `framework service records map to content-free service categories`() {
        assertEquals("playback",ForegroundServiceFailure.service(record(".PlaybackService")))
        assertEquals("transcription",ForegroundServiceFailure.service(record(".work.TranscriptionService")))
        assertEquals("headphone_voice",ForegroundServiceFailure.service(record(".voice.HeadphoneVoiceService")))
        assertEquals("work_manager",ForegroundServiceFailure.service(record("androidx.work.impl.foreground.SystemForegroundService")))
        // Validation builds use another application ID, so Android prints the full class name.
        assertEquals("transcription",ForegroundServiceFailure.service(
            record("dev.distillpod.android.work.TranscriptionService","dev.distillpod.android.validation139")))
        assertEquals("work_manager",ForegroundServiceFailure.service(
            "startForegroundService() not allowed due to mAllowStartForeground false: service dev.distillpod.android/androidx.work.impl.foreground.SystemForegroundService"))
    }

    @Test fun `unknown or absent components never pass the class name through`() {
        assertEquals("other",ForegroundServiceFailure.service(record("com.example.private.SecretService")))
        assertEquals("other",ForegroundServiceFailure.service(record(".work.TranscriptionServiceExtra")))
        assertEquals("unknown",ForegroundServiceFailure.service("Time limit already exhausted for foreground service type mediaProcessing"))
        assertEquals("unknown",ForegroundServiceFailure.service(null))
    }

    @Test fun `only foreground service exception types are classified`() {
        assertTrue(ForegroundServiceFailure.isForegroundServiceType("RemoteServiceException\$ForegroundServiceDidNotStartInTimeException"))
        assertTrue(ForegroundServiceFailure.isForegroundServiceType("ForegroundServiceStartNotAllowedException"))
        assertFalse(ForegroundServiceFailure.isForegroundServiceType("IllegalStateException"))
        assertFalse(ForegroundServiceFailure.isForegroundServiceType(null))
    }

    private lateinit var context:Context
    @Before fun setup() {
        context=ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("diagnostics",0).edit().clear().commit()
        File(context.noBackupFilesDir,"diagnostics").deleteRecursively()
        File(context.noBackupFilesDir,"reliability").deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }
    @After fun finish() {Sentry.close()}

    private fun frameworkCrash(message:String, startSite:Boolean):Throwable {
        val cause=if(startSite) Class.forName("android.app.StackTrace").getConstructor(String::class.java)
            .newInstance("Last startServiceCommon() call for this service was made here") as Throwable else null
        return Class.forName("android.app.RemoteServiceException\$ForegroundServiceDidNotStartInTimeException")
            .getConstructor(String::class.java,Throwable::class.java).newInstance(message,cause) as Throwable
    }

    @Test fun `fatal foreground start timeout records which service and whether this process started it`() {
        val d=Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1",RecordingDiagnosticFileForce())
        val barrier=DiagnosticBarrierProbe(d)
        d.consent(true)
        try {
            Sentry.captureException(frameworkCrash(record(".work.TranscriptionService"),startSite=false))
            barrier.awaitIdle();Sentry.flush(2000)
            val first=d.queue.files().map {it.readText()}.single {it.contains("fgs_service")}
            assertTrue(first,Regex(""""fgs_service"\s*:\s*"transcription"""").containsMatchIn(first))
            assertTrue(first,Regex(""""fgs_start_site"\s*:\s*"none"""").containsMatchIn(first))
            assertFalse(first.contains("ServiceRecord"));assertFalse(first.contains("9f3c2b1"))
            assertFalse(first.contains("TranscriptionService"))
            d.queue.clear()
            Sentry.captureException(frameworkCrash(record("androidx.work.impl.foreground.SystemForegroundService"),startSite=true))
            barrier.awaitIdle();Sentry.flush(2000)
            val second=d.queue.files().map {it.readText()}.single {it.contains("fgs_service")}
            assertTrue(second,Regex(""""fgs_service"\s*:\s*"work_manager"""").containsMatchIn(second))
            assertTrue(second,Regex(""""fgs_start_site"\s*:\s*"recorded"""").containsMatchIn(second))
            assertFalse(second.contains("startServiceCommon"));assertFalse(second.contains("SystemForegroundService"))
        } finally {d.consent(false)}
    }

    @Test fun `handled foreground start refusal names the service category`() {
        val d=Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1",RecordingDiagnosticFileForce())
        val barrier=DiagnosticBarrierProbe(d)
        d.consent(true)
        try {
            d.failure("worker",android.app.ForegroundServiceStartNotAllowedException(
                "startForegroundService() not allowed due to mAllowStartForeground false: service dev.distillpod.android/androidx.work.impl.foreground.SystemForegroundService"))
            barrier.awaitIdle();Sentry.flush(2000)
            val payload=d.queue.files().map {it.readText()}.single {it.contains("fgs_service")}
            assertTrue(payload,Regex(""""fgs_service"\s*:\s*"work_manager"""").containsMatchIn(payload))
            assertFalse(payload.contains("mAllowStartForeground"));assertFalse(payload.contains("SystemForegroundService"))
        } finally {d.consent(false)}
    }
}
