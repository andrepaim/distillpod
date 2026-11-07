package dev.distillpod.android.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import io.sentry.Sentry
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class DiagnosticsTest {
    @get:Rule val temporary=TemporaryFolder()
    private lateinit var context:Context
    @Before fun setup() {
        context=ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("diagnostics",0).edit().clear().commit()
        File(context.noBackupFilesDir,"diagnostics").deleteRecursively()
        File(context.noBackupFilesDir,"reliability").deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }
    @After fun finish() {Sentry.close()}
    @Test fun `fresh installation does not store or send diagnostics`() {
        val diagnostics=Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1")
        diagnostics.event("diagnostics.test");diagnostics.failure("capture",Exception("secret"))
        diagnostics.awaitIdle()
        assertFalse(diagnostics.enabled);assertTrue(diagnostics.queue.files().isEmpty())
    }
    @Test fun `consent permits safe logs and revocation clears pending reports`() {
        val diagnostics=Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1")
        diagnostics.consent(true)
        diagnostics.event("diagnostics.test",source="phone")
        diagnostics.event("private.transcript",reason="secret")
        diagnostics.failure("capture",IllegalArgumentException("SECRET-TRANSCRIPT https://private.example/token"))
        diagnostics.awaitIdle();Sentry.flush(2000)
        val payload=diagnostics.queue.files().joinToString {it.readText()}
        assertTrue(payload.contains("diagnostics.test"))
        assertFalse(payload.contains("SECRET-TRANSCRIPT"));assertFalse(payload.contains("private.example"));assertFalse(payload.contains("private.transcript"))
        assertTrue(payload.contains("invalid_data"))
        diagnostics.consent(false);diagnostics.event("diagnostics.test");diagnostics.awaitIdle()
        assertTrue(diagnostics.queue.files().isEmpty())
    }
    @Test fun `usage consent alone never enables diagnostic errors or operation journal`() {
        val d=Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1")
        d.usageConsent(true)
        d.event("voice.started",reason="phone")
        d.failure("capture",Exception("SECRET"),java.util.UUID.randomUUID().toString())
        d.usage("private podcast title");d.awaitIdle()
        val payload=d.queue.files().joinToString {it.readText()}
        assertTrue(payload.contains("usage.feature"));assertFalse(payload.contains("Diagnostic failure"))
        assertFalse(payload.contains("private podcast title"));assertTrue(d.journal.latest().isEmpty())
        d.usageConsent(false);assertTrue(d.queue.files().isEmpty())
    }
    @Test fun `durable summaries persist before executor and synthetic retries have stable identifiers`() {
        val d=Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1");d.consent(true)
        val job=java.util.UUID.randomUUID().toString()
        d.event("transcription.started",job)
        assertEquals("running",d.journal.latest().single {it.operation==job}.state)
        d.event("transcription.completed",job);d.awaitIdle()
        assertEquals("completed",d.journal.latest().single {it.operation==job}.state)
        assertTrue(d.queue.files().any {it.readText().contains("operation.summary")})
        assertTrue(d.journal.pending().isEmpty())
        d.usageConsent(true);d.consent(false)
        assertTrue(d.usageEnabled);assertTrue(d.journal.latest().isEmpty())
        d.event("voice.started",reason="phone");d.awaitIdle()
        val payload=d.queue.files().joinToString {it.readText()}
        assertTrue(payload.contains("usage.feature"));assertFalse(payload.contains("voice.started"))
        d.usageConsent(false)
    }
    @Test fun `outbox survives reopening and bounds storage and expiry`() {
        val directory=temporary.newFolder()
        val now=System.currentTimeMillis()
        val queue=DiagnosticQueue(directory){now}
        repeat(30){queue.append(ByteArray(240*1024))}
        assertTrue(directory.listFiles()!!.sumOf {it.length()}<=5*1024*1024)
        assertTrue(DiagnosticQueue(directory){now}.files().isNotEmpty())
        directory.listFiles()!!.forEach {it.setLastModified(now-73*60*60*1000L)}
        assertTrue(queue.files().isEmpty())
    }
}
