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
        val d=Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1",RecordingDiagnosticFileForce())
        val barrier=DiagnosticBarrierProbe(d)
        val operation=java.util.UUID.randomUUID().toString()
        dev.distillpod.android.sync.SubscriptionCloudAccess.instrumentation=true
        try {
            d.consent(true)
            d.event("transcription.started",operation)
            d.event("youtube.extraction_completed",operation,reason="succeeded",elapsedMs=123)
            barrier.awaitIdle()
            val payload=d.queue.files().joinToString {it.readText()}
            assertTrue(payload.contains("youtube.extraction_completed"))
            assertTrue(payload.contains(operation));assertTrue(d.journal.latest().isEmpty())
            assertTrue(Regex("\"test_traffic\"\\s*:\\s*\\{[^}]*\"value\"\\s*:\\s*true").containsMatchIn(payload))
        } finally {d.consent(false);dev.distillpod.android.sync.SubscriptionCloudAccess.instrumentation=false}
    }
    @Test fun `routine exhaustion preserves terminal outcomes and reporting gaps`() {
        val d=Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1",RecordingDiagnosticFileForce())
        val barrier=DiagnosticBarrierProbe(d)
        d.consent(true);barrier.awaitIdle()
        repeat(1005) { d.event("transcription.stage",reason="transcribing");barrier.awaitIdle() }
        val job=java.util.UUID.randomUUID().toString()
        d.event("transcription.started",job);barrier.awaitIdle()
        d.event("transcription.completed",job);barrier.awaitIdle()
        val payload=d.queue.files().joinToString {it.readText()}
        assertTrue(Regex(""""routine_dropped"\s*:\s*\{[^}]*"value"\s*:\s*[1-9]""").containsMatchIn(payload))
        assertTrue(d.queue.files().any {it.readText().let {text->text.contains("operation.summary") && text.contains(job) && text.contains("completed")}})
        assertFalse(d.journal.pending().any {it.operation==job && it.state=="completed"})
        assertTrue(d.journal.pending().any {it.operation==job && it.state=="running"})
        d.consent(false)
    }
    @Test fun `terminal summaries replay after rollover and acknowledge once while offline`() {
        val d=Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1",RecordingDiagnosticFileForce())
        val barrier=DiagnosticBarrierProbe(d)
        d.consent(true);barrier.awaitIdle()
        val prefs=context.getSharedPreferences("diagnostics",0)
        prefs.edit().putInt("budget_critical",160).commit()
        val job=java.util.UUID.randomUUID().toString()
        d.event("distillation.started",job);d.event("distillation.finished",job,reason="succeeded");barrier.awaitIdle()
        val row=d.journal.pending().single {it.operation==job && it.state=="completed"}
        assertFalse(d.queue.files().any {it.readText().contains(row.id)})
        prefs.edit().putLong("budget_day",-1).commit()
        d.flushSummaries()
        assertEquals(1,d.queue.files().count {it.name.contains("-priority-") && it.readText().contains(row.id)})
        val count=prefs.getInt("sent",0)
        d.flushSummaries()
        assertEquals(count,prefs.getInt("sent",0))
        assertEquals(1,d.queue.files().count {it.name.contains("-priority-") && it.readText().contains(row.id)})
        d.consent(false)
        assertTrue(d.queue.files().isEmpty());assertTrue(d.journal.pending().isEmpty())
        assertEquals(count,prefs.getInt("sent",0))
    }
    @Test fun `offline routine pressure evicts routine before protected reports`() {
        val directory=temporary.newFolder()
        val q=DiagnosticQueue(directory)
        q.append("terminal".toByteArray(),priority=true)
        repeat(1005){q.append("routine".toByteArray())}
        assertEquals(1000,q.files().size)
        assertTrue(q.files().any {it.readText()=="terminal"})
        assertTrue(DiagnosticQueue(directory).files().any {it.readText()=="terminal"})
        q.clear();assertTrue(q.files().isEmpty())
    }
    @Test fun `repeated flush batches denied summary accounting without hiding terminal rows`() {
        val d=Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1",RecordingDiagnosticFileForce())
        val barrier=DiagnosticBarrierProbe(d)
        d.consent(true);barrier.awaitIdle()
        val prefs=context.getSharedPreferences("diagnostics",0)
        prefs.edit().putInt("budget_routine",800).commit()
        val jobs=List(20){java.util.UUID.randomUUID().toString()}
        jobs.forEach {d.journal.transition("transcription",it,"running")}
        d.journal.transition("transcription",jobs.last(),"completed")
        d.flushSummaries()
        val dropped=prefs.getLong("routine_dropped",0)
        assertEquals(20L,dropped)
        repeat(10){d.flushSummaries()}
        assertEquals(dropped,prefs.getLong("routine_dropped",0))
        assertEquals(20,d.journal.pending().size)
        assertTrue(d.queue.files().any {it.readText().contains(jobs.last())})
        d.consent(false)
        assertTrue(d.queue.files().isEmpty());assertTrue(d.journal.pending().isEmpty())
        assertTrue(prefs.getStringSet("suppressed_summaries",emptySet()).orEmpty().isEmpty())
    }
    @Test fun `concurrent recovery and executor flush do not re-admit acknowledged summaries`() {
        val d=Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1",RecordingDiagnosticFileForce())
        val barrier=DiagnosticBarrierProbe(d)
        d.consent(true);barrier.awaitIdle()
        repeat(30){d.journal.transition("transcription",java.util.UUID.randomUUID().toString(),"completed")}
        val ids=d.journal.pending().map {it.id}
        val start=java.util.concurrent.CountDownLatch(1)
        val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val tasks=List(2){pool.submit {start.await();d.flushSummaries()}}
            start.countDown();tasks.forEach {it.get(10,java.util.concurrent.TimeUnit.SECONDS)}
            ids.forEach {id->assertEquals(1,d.queue.files().count {it.readText().contains(id)})}
            assertTrue(d.journal.pending().isEmpty())
        } finally {pool.shutdownNow();d.consent(false)}
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
    @Test fun `uncorrelated late raw callback is incomplete and cannot terminate a new execution`() {
        val d=Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1",RecordingDiagnosticFileForce())
        val barrier=DiagnosticBarrierProbe(d)
        d.consent(true)
        val c=DiagnosticCorrelation(momentId=java.util.UUID.randomUUID().toString(),
            generationAttemptId=java.util.UUID.randomUUID().toString(),executionId=java.util.UUID.randomUUID().toString())
        d.event("distillation.started",c.momentId,correlation=c)
        d.event("distillation.finished",c.momentId,reason="succeeded")
        barrier.awaitIdle()
        val raw=d.queue.files().map {it.readText()}.single {it.contains("distillation.finished")}
        assertFalse(raw.contains(c.executionId!!))
        assertFalse(raw.contains(c.generationAttemptId!!))
        assertTrue(raw.contains("correlation_complete"))
        assertTrue(raw.contains("observed_at_ms"))
        assertTrue(raw.contains("emitted_at_ms"))
        assertEquals("running",d.journal.latest().single {it.operation==c.momentId}.state)
        d.consent(false)
    }
    @Test fun `fresh installation does not store or send diagnostics`() {
        val diagnostics=Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1",RecordingDiagnosticFileForce())
        val barrier=DiagnosticBarrierProbe(diagnostics)
        diagnostics.event("diagnostics.test");diagnostics.failure("capture",Exception("secret"))
        barrier.awaitIdle()
        assertFalse(diagnostics.enabled);assertTrue(diagnostics.queue.files().isEmpty())
    }
    @Test fun `consent permits safe logs and revocation clears pending reports`() {
        val diagnostics=Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1",RecordingDiagnosticFileForce())
        val barrier=DiagnosticBarrierProbe(diagnostics)
        diagnostics.consent(true)
        diagnostics.event("diagnostics.test",source="phone")
        diagnostics.event("private.transcript",reason="secret")
        diagnostics.failure("capture",IllegalArgumentException("SECRET-TRANSCRIPT https://private.example/token"))
        barrier.awaitIdle();Sentry.flush(2000)
        val payload=diagnostics.queue.files().joinToString {it.readText()}
        assertTrue(payload.contains("diagnostics.test"))
        assertFalse(payload.contains("SECRET-TRANSCRIPT"));assertFalse(payload.contains("private.example"));assertFalse(payload.contains("private.transcript"))
        assertTrue(payload.contains("invalid_data"))
        diagnostics.consent(false);diagnostics.event("diagnostics.test");barrier.awaitIdle()
        assertTrue(diagnostics.queue.files().isEmpty())
    }
    @Test fun `usage consent alone never enables diagnostic errors or operation journal`() {
        val d=Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1",RecordingDiagnosticFileForce())
        val barrier=DiagnosticBarrierProbe(d)
        d.usageConsent(true)
        d.event("voice.started",reason="phone")
        d.failure("capture",Exception("SECRET"),java.util.UUID.randomUUID().toString())
        d.usage("private podcast title");barrier.awaitIdle()
        val payload=d.queue.files().joinToString {it.readText()}
        assertTrue(payload.contains("usage.feature"));assertFalse(payload.contains("Diagnostic failure"))
        assertFalse(payload.contains("private podcast title"));assertTrue(d.journal.latest().isEmpty())
        d.usageConsent(false);assertTrue(d.queue.files().isEmpty())
    }
    @Test fun `durable summaries persist before executor and synthetic retries have stable identifiers`() {
        val d=Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1",RecordingDiagnosticFileForce())
        val barrier=DiagnosticBarrierProbe(d)
        d.consent(true)
        val job=java.util.UUID.randomUUID().toString()
        d.event("transcription.started",job)
        assertEquals("running",d.journal.latest().single {it.operation==job}.state)
        d.event("transcription.completed",job);barrier.awaitIdle()
        assertEquals("completed",d.journal.latest().single {it.operation==job}.state)
        assertTrue(d.queue.files().any {it.readText().contains("operation.summary")})
        assertTrue(d.journal.pending().isEmpty())
        d.usageConsent(true);d.consent(false)
        assertTrue(d.usageEnabled);assertTrue(d.journal.latest().isEmpty())
        d.event("voice.started",reason="phone");barrier.awaitIdle()
        val payload=d.queue.files().joinToString {it.readText()}
        assertTrue(payload.contains("usage.feature"));assertFalse(payload.contains("voice.started"))
        d.usageConsent(false)
    }
    @Test fun `capture requests include failures and saved moments create one dependency job`() {
        val d=Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1",RecordingDiagnosticFileForce())
        val barrier=DiagnosticBarrierProbe(d)
        d.consent(true)
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
        barrier.awaitIdle()
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
