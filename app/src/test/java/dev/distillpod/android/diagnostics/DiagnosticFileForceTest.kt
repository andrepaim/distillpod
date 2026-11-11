package dev.distillpod.android.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import java.io.FileDescriptor
import java.io.SyncFailedException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticFileForceTest {
    @get:Rule val folder = TemporaryFolder()
    private fun id() = UUID.randomUUID().toString()

    @Test fun `journal forces complete temporary records before replacing the published target`() {
        val target = File(folder.root, "journal")
        val job = id()
        var published: ByteArray? = null
        var delivered = false
        val force = RecordingDiagnosticFileForce {
            if(published == null) assertFalse(target.exists()) else assertArrayEquals(published, target.readBytes())
            val temporary = OperationJournal(File(target.path + ".tmp"), { 1000L })
            val row = temporary.latest().single()
            assertEquals(job, row.operation)
            assertEquals("running", row.state)
            assertEquals(delivered, row.delivered)
            assertEquals("release", row.release)
            assertEquals(0L, temporary.dropped)
        }
        val journal = OperationJournal.withFileForce(target, force, now = { 1000L }, release = "release")
        journal.transition("transcription", job, "running")
        assertEquals(1, force.calls)
        published = target.readBytes()
        val row = journal.pending().single()
        delivered = true
        journal.acknowledge(row.id)
        assertEquals(2, force.calls)
        assertTrue(OperationJournal(target, { 1000L }).pending().isEmpty())
        assertTrue(OperationJournal(target, { 1000L }).latest().single().delivered)
    }

    @Test fun `outbox forces full envelope bytes before publishing a new file`() {
        val directory = folder.newFolder("outbox")
        val old = "Existing envelope".toByteArray()
        assertTrue(DiagnosticQueue(directory).append(old))
        val before = directory.listFiles()!!.map { it.name }.toSet()
        val payload = ByteArray(8193) { (it % 127).toByte() }
        val force = RecordingDiagnosticFileForce {
            assertEquals(before, directory.listFiles()!!.filter { it.extension == "envelope" }.map { it.name }.toSet())
            val temporary = directory.listFiles()!!.single { it.extension == "tmp" }
            assertArrayEquals(payload, temporary.readBytes())
        }
        assertTrue(DiagnosticQueue.withFileForce(directory, force).append(payload, priority = true))
        assertEquals(1, force.calls)
        val reopened = DiagnosticQueue(directory).files()
        assertEquals(2, reopened.size)
        assertArrayEquals(payload, reopened.single { it.name.contains("priority-") }.readBytes())
    }

    @Test fun `force failures retain the original exception without publishing journal or outbox changes`() {
        val target = File(folder.root, "journal")
        val initial = OperationJournal(target)
        initial.transition("voice", id(), "running")
        val pending = initial.pending().single()
        val published = target.readBytes()
        val original = SyncFailedException("Synthetic force failure")
        val force = RecordingDiagnosticFileForce { throw original }
        val journal = OperationJournal.withFileForce(target, force)
        assertSame(original, assertThrows(SyncFailedException::class.java) { journal.acknowledge(pending.id) })
        assertArrayEquals(published, target.readBytes())
        assertTrue(OperationJournal(target).pending().any { it.id == pending.id })
        // In-memory mutation is not a filesystem rollback; reopen checks the committed target.
        assertTrue(journal.latest().single().delivered)
        val directory = folder.newFolder("outbox")
        val old = "Existing envelope".toByteArray()
        assertTrue(DiagnosticQueue(directory).append(old))
        val before = directory.listFiles()!!.filter { it.extension == "envelope" }.map { it.name }.toSet()
        assertSame(original, assertThrows(SyncFailedException::class.java) {
            DiagnosticQueue.withFileForce(directory, force).append("New envelope".toByteArray())
        })
        assertEquals(before, directory.listFiles()!!.filter { it.extension == "envelope" }.map { it.name }.toSet())
        assertArrayEquals(old, DiagnosticQueue(directory).files().single().readBytes())
        assertEquals(2, force.calls)
    }

    @Test fun `native default constructors retain clock release positional and trailing callback semantics`() {
        assertThrows(SyncFailedException::class.java) { NativeDiagnosticFileForce.force(FileDescriptor()) }
        var tick = 10L
        val target = File(folder.root, "journal")
        val journal = OperationJournal(target, { 1000L }, { tick }, "retained-release")
        val job = id()
        journal.transition("voice", job, "running")
        tick += 25
        journal.transition("voice", job, "completed")
        val reopened = OperationJournal(target, { 1000L })
        assertEquals("retained-release", reopened.latest().single().release)
        assertEquals(1000L, reopened.latest().single().started)
        assertEquals(25L, reopened.latest().single().elapsed)
        var positionalDrops = 0
        val directory = folder.newFolder("positional")
        val positional = DiagnosticQueue(directory, { 1000L }, { positionalDrops++ })
        assertFalse(positional.append(ByteArray(256 * 1024 + 1)))
        assertEquals(1, positionalDrops)
        assertTrue(positional.append("Native stored bytes".toByteArray()))
        assertTrue(positional.files().single().name.startsWith("1000-"))
        assertArrayEquals("Native stored bytes".toByteArray(), DiagnosticQueue(directory).files().single().readBytes())
        var trailingDrops = 0
        val trailing = DiagnosticQueue(folder.newFolder("trailing"), { 2000L }) { trailingDrops++ }
        assertFalse(trailing.append(ByteArray(256 * 1024 + 1)))
        assertEquals(1, trailingDrops)
        assertTrue(trailing.append("Trailing bytes".toByteArray()))
        assertTrue(trailing.files().single().name.startsWith("2000-"))
    }

    @Test fun `native storage rejects malformed and failed file publication without inventing committed data`() {
        val target = File(folder.root, "journal")
        target.writeBytes("Malformed journal".toByteArray())
        val journal = OperationJournal(target)
        assertTrue(journal.latest().isEmpty())
        assertEquals(1L, journal.dropped)
        journal.transition("voice", id(), "running")
        val published = target.readBytes()
        val temporary = File(target.path + ".tmp")
        assertTrue(temporary.mkdir())
        assertThrows(java.io.IOException::class.java) { journal.acknowledge(journal.pending().single().id) }
        assertArrayEquals(published, target.readBytes())
        assertEquals(1, OperationJournal(target).pending().size)
        val unavailable = File(folder.root, "not-a-directory").apply { writeText("Synthetic blocked parent") }
        assertThrows(java.io.IOException::class.java) { DiagnosticQueue(unavailable).append(byteArrayOf(1)) }
        assertEquals("Synthetic blocked parent", unavailable.readText())
    }

    private fun fixture(force: DiagnosticFileForce, release: () -> Unit = {},
        test: (Context, Diagnostics, DiagnosticBarrierProbe) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("diagnostics", 0).edit().clear().commit()
        File(context.noBackupFilesDir, "diagnostics").deleteRecursively()
        File(context.noBackupFilesDir, "reliability").deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val diagnostics = Diagnostics(context, "https://abc123@o1.ingest.sentry.io/1", force)
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

    @Test fun `explicit diagnostics instance routes both journal and outbox through its force dependency`() {
        val journalForces = AtomicInteger()
        val outboxForces = AtomicInteger()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val force = RecordingDiagnosticFileForce {
            if(File(context.noBackupFilesDir, "reliability/journal.tmp").exists()) journalForces.incrementAndGet()
            else {
                assertTrue(File(context.noBackupFilesDir, "diagnostics").listFiles().orEmpty().any { it.extension == "tmp" })
                outboxForces.incrementAndGet()
            }
        }
        fixture(force) { _, diagnostics, barrier ->
            val job = id()
            diagnostics.event("transcription.started", job)
            barrier.awaitIdle()
            assertTrue(journalForces.get() >= 2)
            assertTrue(outboxForces.get() > 0)
            val row = OperationJournal(File(context.noBackupFilesDir, "reliability/journal")).latest().single { it.operation == job }
            assertTrue(row.delivered)
            assertTrue(diagnostics.queue.files().any { it.readText().contains(row.id) })
        }
    }

    @Test fun `blocked acknowledgement force leaves the original bounded barrier pending until release`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = AtomicBoolean()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val force = RecordingDiagnosticFileForce {
            if(blocking.get() && File(context.noBackupFilesDir, "reliability/journal.tmp").exists()) {
                entered.countDown()
                check(release.await(15, TimeUnit.SECONDS)) { "Controlled force was not released" }
            }
        }
        fixture(force, release::countDown) { _, diagnostics, barrier ->
            val job = id()
            diagnostics.journal.transition("distillation", job, "completed")
            val target = File(context.noBackupFilesDir, "reliability/journal")
            val published = target.readBytes()
            blocking.set(true)
            barrier.executor.execute { diagnostics.flushSummaries() }
            assertTrue("Acknowledgement force callback was not reached", entered.await(5, TimeUnit.SECONDS))
            assertArrayEquals(published, target.readBytes())
            assertFalse(OperationJournal(target).latest().single { it.operation == job }.delivered)
            assertTrue(OperationJournal(File(target.path + ".tmp")).latest().single { it.operation == job }.delivered)
            val original = assertThrows(TimeoutException::class.java) { barrier.awaitIdle() }
            val evidence = original.suppressed.mapNotNull { it.message }.single { it.startsWith("Fixture diagnostics snapshot:") }
            assertTrue(evidence, evidence.contains("CountDownLatch.await"))
            assertTrue(evidence, evidence.contains("queuedFutures=1 done=0 cancelled=0"))
            assertTrue(barrier.executor.queue.filterIsInstance<Future<*>>().any { !it.isDone && !it.isCancelled })
            release.countDown()
            barrier.awaitIdle()
            assertTrue(OperationJournal(File(context.noBackupFilesDir, "reliability/journal")).latest().single { it.operation == job }.delivered)
        }
    }
}
