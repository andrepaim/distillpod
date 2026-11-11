package dev.distillpod.android.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticWallClockTest {
    // A supplied observation in 2100 models a clock correction between boundaries.
    // Neither the host clock nor Robolectric's clocks are changed.
    private val observedAt = 4_102_444_800_000L

    private fun fixture(test: (Diagnostics, DiagnosticBarrierProbe) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("diagnostics", 0).edit().clear().commit()
        File(context.noBackupFilesDir, "diagnostics").deleteRecursively()
        File(context.noBackupFilesDir, "reliability").deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val diagnostics = Diagnostics(context, "https://abc123@o1.ingest.sentry.io/1")
        val barrier = DiagnosticBarrierProbe(diagnostics)
        try {
            diagnostics.consent(true)
            barrier.awaitIdle()
            test(diagnostics, barrier)
        } finally {
            diagnostics.consent(false)
            io.sentry.Sentry.close()
        }
    }

    private fun fields(diagnostics: Diagnostics, event: String): Map<String, Any> {
        val payload = diagnostics.queue.files().map { it.readText() }.single { it.contains(event) }
        val item = payload.lineSequence().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .mapNotNull { it.optJSONArray("items") }.first().getJSONObject(0)
        val attributes = item.getJSONObject("attributes")
        return attributes.keys().asSequence().associateWith { attributes.getJSONObject(it).get("value") }
    }

    @Test fun `both boundaries require present numeric positive wall time evidence`() {
        for(name in listOf("observed_at_ms", "emitted_at_ms")) {
            for(invalid in listOf(null, "1234", true, 0L, -1L, Double.NaN, Double.POSITIVE_INFINITY)) {
                val evidence = mutableMapOf<String, Any>("observed_at_ms" to 1234L, "emitted_at_ms" to 5678L)
                if(invalid == null) evidence.remove(name) else evidence[name] = invalid
                val failure = assertThrows(AssertionError::class.java) { assertDiagnosticWallTimes(evidence) }
                assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains(name))
            }
        }
    }

    @Test fun `context summary preserves a supplied observation independently of emission`() = fixture { diagnostics, barrier ->
        diagnostics.contextSummary(UUID.randomUUID().toString(), ContextPreparationEvidence(), 0, null, observedAt)
        barrier.awaitIdle()
        val times = assertDiagnosticWallTimes(fields(diagnostics, "capture.context_summary"))
        assertEquals(observedAt, times.observedAtMs)
        assertNotEquals("Emission must be sampled separately from the supplied observation", observedAt, times.emittedAtMs)
    }

    @Test fun `attempt summary preserves a supplied observation independently of emission`() = fixture { diagnostics, barrier ->
        val outcome = DistillationOutcome().apply { fail("cancelled"); finish() }
        val correlation = DiagnosticCorrelation(momentId = UUID.randomUUID().toString(),
            generationAttemptId = UUID.randomUUID().toString(), executionId = UUID.randomUUID().toString())
        diagnostics.attemptSummary(outcome, correlation, observedAt)
        barrier.awaitIdle()
        val evidence = fields(diagnostics, "distillation.attempt_summary")
        val times = assertDiagnosticWallTimes(evidence)
        assertEquals(observedAt, times.observedAtMs)
        assertNotEquals("Emission must be sampled separately from the supplied observation", observedAt, times.emittedAtMs)
        assertEquals("cancelled", evidence["stage_outcome"])
        assertEquals(correlation.executionId, evidence["summary_id"])
    }
}
