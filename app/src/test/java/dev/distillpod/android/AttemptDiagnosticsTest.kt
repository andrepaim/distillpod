package dev.distillpod.android

import dev.distillpod.android.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class AttemptDiagnosticsTest {
    private fun id()=UUID.randomUUID().toString()
    private val epoch=id()
    @Test fun `retry comparisons are local flags and never leak model or input text`() {
        val first=MomentAttemptEntity(id(),id(),JSONObject(AttemptDiagnostics.context(false,request=id(),consentEpoch=epoch))
            .put("revision",id()).put("promptVersion",1).toString(),model="private-model")
        val next=MomentAttemptEntity(id(),first.momentId,JSONObject(AttemptDiagnostics.context(true,previous=first,trigger="transcript_changed",consentEpoch=epoch))
            .put("revision",id()).put("promptVersion",2).toString(),model="different-private-model")
        val c=AttemptDiagnostics.correlation(next,first,consentEpoch=epoch)
        assertEquals(first.id,c.previousAttemptId)
        assertEquals(true,c.transcriptChanged);assertEquals(true,c.contextExpanded)
        assertEquals(true,c.modelChanged);assertEquals(true,c.promptChanged)
        assertEquals(false,c.candidateReused)
        assertFalse(c.fields().toString().contains("private-model"))
        assertEquals(true,c.fields()["correlation_complete"])
    }
    @Test fun `explicit resume trigger is consumed and later recovery is distinct`() {
        val a=MomentAttemptEntity(id(),id(),AttemptDiagnostics.context(false,request=id(),consentEpoch=epoch),generationCalls=1,candidateJson="saved")
        val resumed=a.copy(contextJson=AttemptDiagnostics.resume(a.contextJson,"review_resume",epoch))
        val acquired=resumed.copy(contextJson=AttemptDiagnostics.executionContext(resumed,epoch))
        assertEquals("review_resume",AttemptDiagnostics.correlation(acquired,consentEpoch=epoch).retryTrigger)
        val recovered=acquired.copy(contextJson=AttemptDiagnostics.executionContext(acquired,epoch),run=id())
        assertEquals("process_recovery",AttemptDiagnostics.correlation(recovered,consentEpoch=epoch).retryTrigger)
        assertEquals(acquired.id,recovered.id);assertNotEquals(acquired.run,recovered.run)
        assertEquals(true,AttemptDiagnostics.correlation(recovered,consentEpoch=epoch).candidateReused)
    }
    @Test fun `legacy source has explicitly unknown ancestry and comparisons`() {
        val a=MomentAttemptEntity(id(),id(),"""{"expanded":false}""")
        val c=AttemptDiagnostics.correlation(a)
        assertNull(c.captureRequestId);assertNull(c.previousAttemptId);assertNull(c.transcriptChanged)
        assertNull(c.modelChanged);assertNull(c.promptChanged)
        assertEquals("unknown",c.retryTrigger)
        assertEquals(false,c.fields()["correlation_complete"])
    }
}
