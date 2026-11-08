package dev.distillpod.android.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DistillationOutcomeTest {
    @Test fun `new attempt distinguishes not run from unknown model and counts failed requests`() {
        val summary=DistillationOutcome()
        assertEquals("not_run",summary.fields()["review_outcome"])
        summary.begin("generation")
        summary.fail("quota")
        val fields=summary.fields()
        assertEquals("generation",fields["stage"])
        assertEquals("quota",fields["stage_outcome"])
        assertEquals(1,fields["generation_attempted_calls"])
        assertEquals("unknown",fields["generator_model"])
        assertEquals("not_run",fields["reviewer_model"])
    }
    @Test fun `model validation rejects private strings and timing remains bounded`() {
        val summary=DistillationOutcome()
        summary.begin("generation")
        summary.response("https://private.example/secret")
        summary.generated("needs_context")
        assertEquals("unknown",summary.fields()["generator_model"])
        assertEquals("generator_needs_context",summary.fields()["stage_outcome"])
        assertFalse(summary.fields().toString().contains("private"))
        assertTrue((summary.fields()["generation_elapsed_ms"] as Long)>=0)
    }
    @Test fun `terminal guard prevents duplicate terminal summaries`() {
        val summary=DistillationOutcome()
        assertTrue(summary.finish())
        assertFalse(summary.finish())
    }
}
