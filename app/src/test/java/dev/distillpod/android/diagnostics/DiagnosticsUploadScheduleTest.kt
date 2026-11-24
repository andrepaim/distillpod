package dev.distillpod.android.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
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
import java.io.IOException
import java.util.concurrent.TimeUnit

/** #300: scheduling and reporting around the upload worker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class DiagnosticsUploadScheduleTest {
    private lateinit var context:Context
    private val dsn="https://abc123@o1.ingest.sentry.io/1"

    @Before fun setup() {
        context=ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("diagnostics",0).edit().clear().commit()
        File(context.noBackupFilesDir,"diagnostics").deleteRecursively()
        File(context.noBackupFilesDir,"reliability").deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }
    @After fun finish() {Sentry.close()}

    private fun work(name:String)=WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get(5,TimeUnit.SECONDS)
        .filter {it.state==WorkInfo.State.ENQUEUED || it.state==WorkInfo.State.BLOCKED}

    @Test fun `requests left backing off by an older version are replaced once on upgrade`() {
        val manager=WorkManager.getInstance(context)
        val legacy=OneTimeWorkRequestBuilder<DiagnosticsUploadWorker>().setInitialDelay(5,TimeUnit.HOURS).build()
        manager.enqueueUniqueWork(Diagnostics.UPLOAD_WORK,ExistingWorkPolicy.KEEP,legacy).result.get()
        val legacyPeriodic=PeriodicWorkRequestBuilder<DiagnosticsUploadWorker>(15,TimeUnit.MINUTES).build()
        manager.enqueueUniquePeriodicWork(Diagnostics.RECOVERY_WORK,ExistingPeriodicWorkPolicy.KEEP,legacyPeriodic).result.get()
        // Consent given by the older version.
        context.getSharedPreferences("diagnostics",0).edit().putBoolean("enabled",true).commit()

        Diagnostics(context,dsn,RecordingDiagnosticFileForce())
        val upload=work(Diagnostics.UPLOAD_WORK).single()
        val recovery=work(Diagnostics.RECOVERY_WORK).single()
        assertNotEquals(legacy.id,upload.id)
        assertTrue(Diagnostics.UPLOAD_TAG in upload.tags)
        assertNotEquals(legacyPeriodic.id,recovery.id)
        assertTrue(Diagnostics.RECOVERY_TAG in recovery.tags)

        // Later starts keep the pending requests.
        Sentry.close()
        Diagnostics(context,dsn,RecordingDiagnosticFileForce())
        assertEquals(upload.id,work(Diagnostics.UPLOAD_WORK).single().id)
        assertEquals(recovery.id,work(Diagnostics.RECOVERY_WORK).single().id)
    }

    @Test fun `a resumed stall is reported once in diagnostics health and shown in the status`() {
        val d=Diagnostics(context,dsn,RecordingDiagnosticFileForce())
        val barrier=DiagnosticBarrierProbe(d)
        d.consent(true);barrier.awaitIdle()
        d.queue.files().forEach {assertTrue(it.setLastModified(System.currentTimeMillis()-3*60*60_000L))}
        val pending=d.queue.files().size
        assertTrue(pending>0)

        repeat(3) {d.uploadFinished(d.uploader {throw IOException("offline")}.drain {true})}
        val failures=d.uploadState.consecutiveFailures
        assertTrue(failures>=3)
        assertTrue(d.status.value.contains("($failures attempts in a row)"))
        assertTrue(d.reportingHealth.contains("$failures failed upload attempts in a row"))
        assertTrue(d.reportingHealth.contains("oldest pending report 3 h"))

        val accepted=d.uploader {DiagnosticDelivery.Receipt(DiagnosticDelivery.Outcome.ACCEPTED,code=200)}.drain {true}
        assertEquals(DiagnosticUploader.Next.Done,accepted)
        d.uploadFinished(accepted)
        assertTrue(d.status.value.startsWith("Last upload"))
        val health=d.queue.files().map {it.readText()}.filter {it.contains("diagnostics.health")}
        assertEquals(1,health.size)
        assertTrue(health.single().contains("upload_stall_wait_ms"))
        assertTrue(Regex(""""upload_stall_failures"\s*:\s*\{[^}]*"value"\s*:\s*$failures\b""").containsMatchIn(health.single()))
        assertNull(d.uploadState.pendingStallReport())

        d.flushSummaries()
        assertEquals(1,d.queue.files().count {it.readText().contains("diagnostics.health")})
        d.consent(false)
        assertEquals(0,d.uploadState.consecutiveFailures)
    }

    @Test fun `the periodic request keeps a pending upload while the one-time request appends its successor`() {
        val d=Diagnostics(context,dsn,RecordingDiagnosticFileForce())
        d.consent(true)
        val pending=work(Diagnostics.UPLOAD_WORK).single()
        d.scheduleUpload(60_000,continuation=false)
        assertEquals(pending.id,work(Diagnostics.UPLOAD_WORK).single().id)
        d.scheduleUpload(60_000,continuation=true)
        val chain=work(Diagnostics.UPLOAD_WORK)
        assertEquals(2,chain.size)
        assertTrue(chain.any {it.id==pending.id})
        d.consent(false)
    }

    @Test fun `an invalid endpoint is refused without a retry loop`() {
        assertNull(DiagnosticsUploadWorker.endpoint("https://abc123@example.com/1"))
        assertNull(DiagnosticsUploadWorker.endpoint(""))
        assertEquals("https://o1.ingest.sentry.io/api/1/envelope/",DiagnosticsUploadWorker.endpoint(dsn)!!.url)
    }
}
