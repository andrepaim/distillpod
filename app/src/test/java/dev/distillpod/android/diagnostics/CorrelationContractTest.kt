package dev.distillpod.android.diagnostics

import org.junit.Test
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class CorrelationContractTest {
    @get:Rule val folder=TemporaryFolder()
    private fun id()=UUID.randomUUID().toString()
    @Test fun `attempt and execution identities survive restart and delayed replay`() {
        val file=File(folder.root,"journal")
        val c=DiagnosticCorrelation(captureRequestId=id(),momentId=id(),generationAttemptId=id(),
            executionId=id(),transcriptRevisionId=id(),retryTrigger="initial",contextExpanded=false,candidateReused=false)
        val journal=OperationJournal(file,{2000})
        journal.transition("distillation",c.momentId!!,"running",correlation=c,observedAt=1000)
        journal.transition("distillation",c.momentId,"completed",correlation=c,observedAt=1200)
        val before=journal.pending()
        val replay=OperationJournal(file,{5000}).pending()
        assertEquals(before,replay)
        assertEquals(1000L,replay.first().started)
        assertEquals(1200L,replay.last().updated)
        assertEquals(c,replay.last().correlation)
        assertEquals(c.generationAttemptId,replay.last().correlation!!.fields()["generation_attempt_id"])
    }
    @Test fun `late responses cannot overwrite another execution or cancellation`() {
        val journal=OperationJournal(File(folder.root,"journal"))
        val first=DiagnosticCorrelation(momentId=id(),generationAttemptId=id(),executionId=id())
        val next=first.copy(executionId=id(),retryTrigger="review_resume",candidateReused=true)
        journal.transition("distillation",first.momentId!!,"running",correlation=first)
        journal.transition("distillation",first.momentId,"running",correlation=next)
        journal.transition("distillation",first.momentId,"completed",correlation=first)
        assertEquals(next,journal.latest().single().correlation)
        assertEquals("running",journal.latest().single().state)
        journal.transition("distillation",first.momentId,"cancelled",correlation=next)
        journal.transition("distillation",first.momentId,"completed",correlation=next)
        assertEquals("cancelled",journal.latest().single().state)
        assertEquals(first.generationAttemptId,next.generationAttemptId)
        assertNotEquals(first.executionId,next.executionId)
    }
    @Test fun `dependency replacement and explicit retry remain separate revisions`() {
        val journal=OperationJournal(File(folder.root,"journal"))
        val first=DiagnosticCorrelation(captureRequestId=id(),momentId=id(),generationAttemptId=id(),retryTrigger="initial")
        val prepared=first.copy(transcriptRevisionId=id(),executionId=id(),contextExpanded=false)
        journal.transition("distillation",first.momentId!!,"waiting",correlation=first)
        journal.transition("distillation",first.momentId,"waiting",correlation=prepared)
        val retry=prepared.copy(generationAttemptId=id(),executionId=id(),previousAttemptId=first.generationAttemptId,
            transcriptRevisionId=id(),retryTrigger="expanded_context",contextExpanded=true,transcriptChanged=true)
        journal.transition("distillation",first.momentId,"running",correlation=retry)
        assertEquals(3,journal.pending().size)
        assertEquals(first.generationAttemptId,retry.previousAttemptId)
        assertEquals(true,retry.fields()["transcript_changed"])
    }
    @Test fun `legacy identity never becomes a fabricated generation link`() {
        val journal=OperationJournal(File(folder.root,"journal"))
        journal.transition("distillation",id(),"running")
        assertNull(journal.latest().single().correlation)
        assertEquals(false,DiagnosticCorrelation().fields()["correlation_complete"])
        assertFalse(DiagnosticCorrelation().fields().containsKey("generation_attempt_id"))
    }
    @Test(expected=IllegalArgumentException::class) fun `reject content identifiers`() {
        DiagnosticCorrelation(momentId="private transcript")
    }
    @Test(expected=IllegalArgumentException::class) fun `reject content retry triggers`() {
        DiagnosticCorrelation(retryTrigger="private prompt")
    }
}
