package dev.distillpod.android.diagnostics
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ContextPreparationEvidenceTest {
    @Test fun `serialized schema cannot carry arbitrary content or reasons`() {
        val secret="private transcript https://private.invalid source title hash prompt candidate exception"
        val evidence=ContextPreparationEvidence()
        evidence.source=secret
        evidence.outcome=secret
        evidence.reason=secret
        val payload=JSONObject(evidence.fields(13)).toString()
        assertFalse(payload.contains(secret))
        assertFalse(payload.contains("private.invalid"))
        assertEquals("other",evidence.fields(13)["transcript_source"])
        assertEquals("failed",evidence.fields(13)["outcome"])
        assertEquals("internal",evidence.fields(13)["context_reason"])
        assertEquals(1,evidence.fields(13)["context_schema_version"])
        assertFalse(evidence.fields(13).containsKey("missing_duration_ms"))
        assertEquals(DiagnosticBudget.Lane.CRITICAL,DiagnosticBudget.lane("capture.context_summary"))
    }
    @Test fun `unknown measurements are not zeros masquerading as success`() {
        val evidence=ContextPreparationEvidence()
        assertEquals(false,evidence.fields(0)["coverage_measured"])
        assertEquals(false,evidence.fields(0)["segments_measured"])
        assertEquals(false,evidence.fields(0)["frozen_context"])
        evidence.expanded=true
        assertEquals(180000L,evidence.fields(0)["backward_window_ms"])
    }
}
