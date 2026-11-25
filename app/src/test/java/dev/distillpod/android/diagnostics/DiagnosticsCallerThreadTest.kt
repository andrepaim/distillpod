package dev.distillpod.android.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #293: playback calls Diagnostics from the main thread. No public reporting call may do file I/O
 * there, or wait on the diagnostics lock while the executor holds it across a file force.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticsCallerThreadTest {
    private fun id() = UUID.randomUUID().toString()
    private val dsn = "https://abc123@o1.ingest.sentry.io/1"

    private fun fixture(force: DiagnosticFileForce, release: () -> Unit = {},
        test: (Context, Diagnostics, DiagnosticBarrierProbe) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("diagnostics", 0).edit().clear().commit()
        File(context.noBackupFilesDir, "diagnostics").deleteRecursively()
        File(context.noBackupFilesDir, "reliability").deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val diagnostics = Diagnostics(context, dsn, force)
        val barrier = DiagnosticBarrierProbe(diagnostics)
        var failure: Throwable? = null
        try {
            diagnostics.consent(true)
            barrier.awaitIdle()
            test(context, diagnostics, barrier)
        } catch(problem: Throwable) { failure = problem }
        finally {
            // Release an owned gate BEFORE consent teardown can wait for its lock.
            for(cleanup in listOf(release, { diagnostics.consent(false) }, { io.sentry.Sentry.close() }, {
                barrier.executor.shutdown()
                check(barrier.executor.awaitTermination(5, TimeUnit.SECONDS)) { "Owned fixture executor did not finish" }
            })) {
                try { cleanup() } catch(problem: Throwable) {
                    if(failure == null) failure = problem else failure!!.addSuppressed(problem)
                }
            }
        }
        failure?.let { throw it }
    }

    /** Runs [call] on a separate caller thread; fails if it does not return within two seconds. */
    private fun returnsPromptly(label: String, call: () -> Unit) {
        val caller = Executors.newSingleThreadExecutor()
        try {
            try { caller.submit(call).get(2, TimeUnit.SECONDS) }
            catch(timeout: java.util.concurrent.TimeoutException) { throw AssertionError("$label blocked its caller", timeout) }
        } finally { caller.shutdown() }
    }

    /** Holds the single diagnostics worker until released, without holding the diagnostics lock. */
    private fun gate(barrier: DiagnosticBarrierProbe): CountDownLatch {
        val release = CountDownLatch(1)
        val running = CountDownLatch(1)
        barrier.executor.execute { running.countDown(); check(release.await(15, TimeUnit.SECONDS)) { "Gate was not released" } }
        assertTrue("Gate did not start", running.await(5, TimeUnit.SECONDS))
        return release
    }

    private fun journal(context: Context) = OperationJournal(File(context.noBackupFilesDir, "reliability/journal"))
    private fun payload(diagnostics: Diagnostics) = diagnostics.queue.files().joinToString { it.readText() }

    @Test fun `reporting calls return while a file force is blocked inside the diagnostics lock`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = AtomicBoolean()
        val force = RecordingDiagnosticFileForce {
            if(blocking.get()) {
                entered.countDown()
                check(release.await(15, TimeUnit.SECONDS)) { "Controlled force was not released" }
            }
        }
        fixture(force, release::countDown) { context, diagnostics, barrier ->
            diagnostics.usageConsent(true)
            barrier.awaitIdle()
            val moment = id()
            val correlation = DiagnosticCorrelation(momentId = moment, generationAttemptId = id(), executionId = id(),
                collectionEpoch = diagnostics.correlationEpoch)
            blocking.set(true)
            // The playback ANR path: a journal kind whose transition used to be forced on the caller.
            returnsPromptly("playback.service_started") { diagnostics.event("playback.service_started") }
            // Whoever reached the force first now holds it, and the executor's force runs inside the lock.
            assertTrue("No force was reached", entered.await(5, TimeUnit.SECONDS))
            returnsPromptly("playback.state") { diagnostics.event("playback.state", reason = "playing", source = "phone") }
            returnsPromptly("failure") { diagnostics.failure("playback", IOException("SECRET-PATH /private")) }
            returnsPromptly("usage") { diagnostics.usage("capture", "phone") }
            returnsPromptly("contextSummary") {
                diagnostics.contextSummary(moment, ContextPreparationEvidence().apply { outcome = "queued"; reason = "ready" }, 5, correlation)
            }
            returnsPromptly("attemptSummary") { diagnostics.attemptSummary(DistillationOutcome().also { it.finish() }, correlation) }
            returnsPromptly("transcriptQuality") { diagnostics.transcriptQuality(id(), "whisper", "base", "en", 1000, 1000, false) }
            returnsPromptly("playback.service_stopped") { diagnostics.event("playback.service_stopped") }
            blocking.set(false)
            release.countDown()
            barrier.awaitIdle()
            val playback = journal(context).latest().single { it.kind == "playback" }
            assertEquals("completed", playback.state)
            val text = payload(diagnostics)
            listOf("playback.service_started", "playback.state", "Diagnostic failure", "usage.feature", "capture.context_summary",
                "distillation.attempt_summary", "transcription.quality", "playback.service_stopped").forEach {
                assertTrue("Missing deferred $it", text.contains(it))
            }
            assertFalse(text.contains("SECRET-PATH"))
            assertTrue(text.contains(playback.id))
        }
    }

    @Test fun `transitions keep call order observation time and elapsed ticks across the executor hop`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val target = File(context.noBackupFilesDir, "reliability/journal")
        val summaryId = Regex(""""summary_id"\s*:\s*\{[^}]*"value"\s*:\s*"([0-9a-f-]{36})"""")
        val orphanSummaries = mutableListOf<String>()
        val checkedSummaries = java.util.concurrent.atomic.AtomicInteger()
        val force = RecordingDiagnosticFileForce {
            // A summary may only be stored after its transition is committed to the journal.
            val pending = File(context.noBackupFilesDir, "diagnostics").listFiles().orEmpty().filter { it.extension == "tmp" }
            pending.flatMap { summaryId.findAll(it.readText()).map { match -> match.groupValues[1] }.toList() }.forEach { row ->
                checkedSummaries.incrementAndGet()
                if(!target.exists() || OperationJournal(target).latest().none { it.id == row } &&
                    OperationJournal(target).pending().none { it.id == row }) orphanSummaries += row
            }
        }
        fixture(force) { _, diagnostics, barrier ->
            val job = id()
            val moment = id()
            val correlation = DiagnosticCorrelation(momentId = moment, generationAttemptId = id(), executionId = id(),
                collectionEpoch = diagnostics.correlationEpoch)
            val release = gate(barrier)
            val before = System.currentTimeMillis()
            diagnostics.event("transcription.started", job)
            val startedReturned = System.currentTimeMillis()
            Thread.sleep(300)
            diagnostics.event("transcription.queued", job, reason = "wifi_required")
            diagnostics.event("transcription.stage", job, reason = "queued")
            diagnostics.event("transcription.partial", job, reason = "partial")
            diagnostics.event("voice.started", reason = "phone")
            diagnostics.event("voice.finished", reason = "succeeded")
            diagnostics.event("distillation.started", moment, correlation = correlation)
            diagnostics.event("distillation.finished", moment, reason = "succeeded", correlation = correlation)
            val returned = System.currentTimeMillis()
            // Nothing is applied on the caller: the worker is still held.
            assertTrue(diagnostics.journal.latest().isEmpty())
            Thread.sleep(100)
            release.countDown()
            barrier.awaitIdle()
            assertEquals("partial", OperationJournal(target).latest().single { it.operation == job }.state)
            // The full ordered history (latest() keeps only the newest row per operation).
            val history = org.robolectric.util.ReflectionHelpers.getField<List<OperationJournal.Row>>(diagnostics.journal, "rows").toList()
            val ofJob = history.filter { it.operation == job }
            assertEquals(listOf("running", "waiting", "partial"), ofJob.map { it.state })
            assertEquals("wifi_required", ofJob[1].reason)
            assertEquals(ofJob.map { it.sequence }.sorted(), ofJob.map { it.sequence })
            // Observation time is the call time, not the later executor time.
            assertTrue(ofJob[0].updated in before..startedReturned)
            assertTrue(ofJob.all { it.updated <= returned })
            // Elapsed uses call-time monotonic ticks: the calls were 300 ms apart, but applied together.
            assertTrue("elapsed ${ofJob[2].elapsed}", ofJob[2].elapsed >= 300)
            val voice = history.filter { it.kind == "voice" }
            assertEquals(listOf("running", "completed"), voice.map { it.state })
            assertEquals(1, voice.map { it.operation }.toSet().size)
            val distillation = history.filter { it.operation == moment }
            assertEquals(listOf("running", "completed"), distillation.map { it.state })
            assertTrue(distillation.all { it.correlation == correlation.copy(collectionEpoch = null) })
            // Every summary was flushed after its committed transition, and all were acknowledged.
            assertTrue(checkedSummaries.get() >= 7)
            assertEquals(emptyList<String>(), orphanSummaries)
            assertTrue(diagnostics.journal.pending().isEmpty())
            val text = payload(diagnostics)
            ofJob.forEach { assertTrue("Summary ${it.state} missing", text.contains(it.id)) }
        }
    }

    @Test fun `diagnostic consent revocation discards pending transitions logs and failures`() {
        fixture(RecordingDiagnosticFileForce()) { context, diagnostics, barrier ->
            val stale = id()
            val release = gate(barrier)
            diagnostics.event("transcription.started", stale)
            diagnostics.failure("transcription", IOException("Synthetic"), stale)
            diagnostics.contextSummary(stale, ContextPreparationEvidence(), 5, null)
            // The worker holds no diagnostics lock, so consent proceeds while the work is still pending.
            returnsPromptly("revocation") { diagnostics.consent(false); diagnostics.consent(true) }
            release.countDown()
            barrier.awaitIdle()
            assertTrue(journal(context).latest().none { it.operation == stale })
            val text = payload(diagnostics)
            assertFalse(text.contains(stale))
            assertFalse(text.contains("Diagnostic failure"))
            val fresh = id()
            diagnostics.event("transcription.started", fresh)
            barrier.awaitIdle()
            assertEquals("running", journal(context).latest().single { it.operation == fresh }.state)
            assertTrue(payload(diagnostics).contains(fresh))
        }
    }

    @Test fun `a generation change keeps the observed transition but discards its pending log`() {
        fixture(RecordingDiagnosticFileForce()) { context, diagnostics, barrier ->
            val job = id()
            val release = gate(barrier)
            diagnostics.event("transcription.started", job)
            diagnostics.failure("transcription", IOException("Synthetic"), job)
            returnsPromptly("usage toggle") { diagnostics.usageConsent(true) }
            release.countDown()
            barrier.awaitIdle()
            // As before #293: the transitions were observed under diagnostic consent and are retained,
            // while executor work queued before the generation change is discarded.
            assertEquals("failed", journal(context).latest().single { it.operation == job }.state)
            assertFalse(diagnostics.queue.files().any { it.readText().contains("transcription.started") })
            assertFalse(payload(diagnostics).contains("Diagnostic failure"))
            // A later flush still reports the retained transition.
            diagnostics.event("transcription.completed", job)
            barrier.awaitIdle()
            assertTrue(payload(diagnostics).contains(journal(context).latest().single { it.operation == job }.id))
        }
    }
}
