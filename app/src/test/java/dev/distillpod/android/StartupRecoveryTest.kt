package dev.distillpod.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.android.diagnostics.DiagnosticBarrierProbe
import dev.distillpod.android.diagnostics.Diagnostics
import dev.distillpod.android.diagnostics.RecordingDiagnosticFileForce
import io.sentry.Sentry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupRecoveryTest {
    private val ran = mutableListOf<String>()
    private val failures = mutableListOf<Pair<String, Exception>>()
    private val ready = CompletableDeferred<Unit>()

    private fun step(name: String, error: Exception? = null) = StartupStep(name) {
        ran += name
        if (error != null) throw error
    }

    private suspend fun start(prerequisite: suspend () -> Unit = {}, recovery: List<StartupStep>, afterReady: List<StartupStep> = emptyList()) =
        StartupRecovery.run(ready, prerequisite, recovery, afterReady) { name, error -> failures += name to error }

    @After fun finish() = Sentry.close()

    @Test fun `a failing recovery step is reported and the later steps and readiness still happen`() = runBlocking {
        val corrupt = IllegalStateException("corrupt legacy database")
        val readyWhenAutomationStarts = mutableListOf<Boolean>()
        start(recovery = listOf(step("journal"), step("legacy_import", corrupt), step("downloads"), step("transcription")),
            afterReady = listOf(StartupStep("automation") { readyWhenAutomationStarts += ready.isCompleted; ran += "automation" },
                step("saved_captures", IOException("work manager")), step("last")))
        assertEquals(listOf("journal", "legacy_import", "downloads", "transcription", "automation", "saved_captures", "last"), ran)
        assertTrue(ready.isCompleted); assertFalse(ready.isCancelled)
        ready.await()
        assertEquals(listOf(true), readyWhenAutomationStarts)
        assertEquals(listOf("legacy_import", "saved_captures"), failures.map { it.first })
        assertSame(corrupt, failures.first().second)
    }

    @Test fun `readiness waits for every recovery step, failed or not`() = runBlocking {
        val readyDuringRecovery = mutableListOf<Boolean>()
        start(recovery = listOf(step("first", IllegalStateException()), StartupStep("second") { readyDuringRecovery += ready.isCompleted }))
        assertEquals(listOf(false), readyDuringRecovery)
        assertTrue(ready.isCompleted)
    }

    @Test fun `an unusable database fails readiness and skips recovery that depends on it`() = runBlocking {
        val broken = IllegalStateException("migration failed")
        start(prerequisite = { throw broken }, recovery = listOf(step("journal")), afterReady = listOf(step("automation")))
        assertTrue(ran.isEmpty())
        assertEquals(listOf(StartupRecovery.DATABASE to broken), failures)
        // Coroutine stack-trace recovery may hand awaiters a copy of the same failure.
        val awaited = runCatching { ready.await() }.exceptionOrNull()
        assertTrue(awaited is IllegalStateException && awaited.message == broken.message)
    }

    @Test fun `cancellation stops startup and releases readiness awaiters`() = runBlocking {
        val run = async { start(recovery = listOf(step("first"), StartupStep("hangs") { awaitCancellation() }, step("never"))) }
        while (ran.isEmpty()) yield()
        run.cancel()
        assertTrue(runCatching { run.await() }.exceptionOrNull() is CancellationException)
        assertTrue(ready.isCancelled)
        assertEquals(listOf("first"), ran)
        assertTrue("Cancellation is not a failure", failures.isEmpty())
    }

    @Test fun `a startup failure reaches consented diagnostics as a content-free event`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("diagnostics", 0).edit().clear().commit()
        File(context.noBackupFilesDir, "diagnostics").deleteRecursively()
        File(context.noBackupFilesDir, "reliability").deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val diagnostics = Diagnostics(context, "https://abc123@o1.ingest.sentry.io/1", RecordingDiagnosticFileForce())
        val barrier = DiagnosticBarrierProbe(diagnostics)
        try {
            diagnostics.consent(true)
            StartupRecovery.report(diagnostics, "legacy_import", IllegalStateException("SECRET /data/user/0/private.db"))
            barrier.awaitIdle(); Sentry.flush(2000)
            val payload = diagnostics.queue.files().joinToString { it.readText() }
            assertTrue(payload.contains("startup.failed"))
            assertFalse(payload.contains("SECRET")); assertFalse(payload.contains("private.db"))
        } finally { diagnostics.consent(false) }
    }
}
