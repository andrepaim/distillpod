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
    @Test fun `owner acceptance emits marked logs without replacing real operation history`() {
        val d=Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1")
        val operation=java.util.UUID.randomUUID().toString()
        dev.distillpod.android.sync.SubscriptionCloudAccess.instrumentation=true
        try {
            d.consent(true)
            d.event("transcription.started",operation)
            d.event("youtube.extraction_completed",operation,reason="succeeded",elapsedMs=123)
            d.awaitIdle()
            val payload=d.queue.files().joinToString {it.readText()}
            assertTrue(payload.contains("youtube.extraction_completed"))
            assertTrue(payload.contains(operation));assertTrue(d.journal.latest().isEmpty())
            assertTrue(Regex("\"test_traffic\"\\s*:\\s*\\{[^}]*\"value\"\\s*:\\s*true").containsMatchIn(payload))
        } finally {d.consent(false);dev.distillpod.android.sync.SubscriptionCloudAccess.instrumentation=false}
    }
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
    @Test fun `capture requests include failures and saved moments create one dependency job`() {
        val d=Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1");d.consent(true)
        val request=java.util.UUID.randomUUID().toString();val moment=java.util.UUID.randomUUID().toString()
        val db=androidx.room.Room.inMemoryDatabaseBuilder(context,dev.distillpod.android.data.AppDatabase::class.java).build()
        try {
            val repo=dev.distillpod.android.data.CaptureRepository(db,object:dev.distillpod.android.ai.AiGateway {
                override suspend fun complete(instructions:String,input:String,onDelta:(String)->Unit):dev.distillpod.android.ai.AiText=error("Unused")
            },{false},{},{},diagnostics=d)
            kotlinx.coroutines.runBlocking {try {repo.save("missing",-1,false,source="phone");fail("Invalid position must fail")}catch(_:IllegalArgumentException){}}
        } finally {db.close()}
        d.event("capture.started",request);d.event("capture.completed",request,related=moment)
        d.event("capture.saved",moment,reason="created")
        d.event("capture.prepared",moment,reason="waiting_auth")
        d.awaitIdle()
        assertEquals(setOf("failed","completed"),d.journal.latest().filter {it.kind=="capture"}.map {it.state}.toSet())
        assertEquals("waiting_auth",d.journal.latest().single {it.kind=="distillation"}.reason)
        assertEquals(3,d.journal.latest().size)
        d.consent(false)
    }
    @Test fun `queued stage cannot erase a network hold and partial text is not completed`() {
        val d=Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1");d.consent(true)
        val job=java.util.UUID.randomUUID().toString()
        d.event("transcription.started",job)
        d.event("transcription.queued",job,reason="wifi_required")
        d.event("transcription.stage",job,reason="queued")
        assertEquals("waiting",d.journal.latest().single().state)
        assertEquals("wifi_required",d.journal.latest().single().reason)
        d.event("transcription.partial",job,reason="partial")
        assertEquals("partial",d.journal.latest().single().state)
        d.consent(false)
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
