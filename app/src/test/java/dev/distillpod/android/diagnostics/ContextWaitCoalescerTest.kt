package dev.distillpod.android.diagnostics

import dev.distillpod.core.TimeRange
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ContextWaitCoalescerTest {
    private fun id()=UUID.randomUUID().toString()
    private fun correlation()=DiagnosticCorrelation(momentId=id(),generationAttemptId=id(),executionId=id())
    private fun evidence(start:Long=0,revision:String?=null,coverage:List<TimeRange> = emptyList())=
        ContextPreparationEvidence().apply {
            outcome="transcript_required";reason="coverage_missing"
            waitInput=ContextWaitInput.create(start,100,TimeRange(0,1000),1000,revision,coverage)
        }
    @Test fun `only successful identical waits coalesce while changed and terminal facts retry`() {
        val cache=ContextWaitCoalescer();val c=correlation();val e=evidence();var writes=0
        fun record(success:Boolean=true)=cache.record(c.momentId!!,e,c,"epoch",1) {writes++;success}
        record(false);record(false);assertEquals(2,writes)
        record();record();assertEquals(3,writes)
        e.reason="source_mismatch";record(false)
        e.reason="coverage_missing";record();assertEquals(5,writes)
        e.outcome="queued";record();record();assertEquals(7,writes)
        e.outcome="transcript_required";record();assertEquals(8,writes)
    }
    @Test fun `actual input geometry revision and execution identity stay distinct`() {
        val cache=ContextWaitCoalescer();val c=correlation();var writes=0
        fun record(e:ContextPreparationEvidence,corr:DiagnosticCorrelation=c)=
            cache.record(corr.momentId!!,e,corr,"epoch",1) {writes++;true}
        record(evidence());record(evidence());assertEquals(1,writes)
        record(evidence(start=1))
        record(evidence(coverage=listOf(TimeRange(0,10))))
        record(evidence(coverage=listOf(TimeRange(10,20))))
        record(evidence(revision=id()))
        record(evidence(),c.copy(executionId=id()))
        record(evidence(),c.copy(generationAttemptId=id()))
        record(evidence(),c.copy(momentId=id()))
        assertEquals(8,writes)
    }
    @Test fun `cache is bounded and resets at epoch day explicit clear and process lifetime`() {
        var cache=ContextWaitCoalescer(2);var writes=0;val a=correlation();val b=correlation();val c=correlation()
        fun record(corr:DiagnosticCorrelation,epoch:String="epoch",day:Long=1)=
            cache.record(corr.momentId!!,evidence(),corr,epoch,day) {writes++;true}
        record(a);record(b);record(a);record(c)
        assertEquals(2,cache.size);assertEquals(3,writes)
        record(b);assertEquals(4,writes);assertEquals(2,cache.size)
        record(b,day=2);record(b,epoch="new",day=2);assertEquals(6,writes)
        cache.clear();assertEquals(0,cache.size);record(b,epoch="new",day=2)
        cache=ContextWaitCoalescer(2);record(b,epoch="new",day=2);assertEquals(8,writes)
    }
    @Test fun `unbounded or unavailable inputs and absent durable identities never suppress evidence`() {
        val fragmented=List(ContextWaitInput.MAX_RANGES+1) {TimeRange(it*2L,it*2L+1)}
        val cache=ContextWaitCoalescer();val c=correlation();var writes=0
        val e=evidence(coverage=fragmented);assertNull(e.waitInput)
        repeat(2) {cache.record(c.momentId!!,e,c,"epoch",1) {writes++;true}}
        repeat(2) {cache.record(c.momentId!!,evidence(),c.copy(executionId=null),"epoch",1) {writes++;true}}
        assertEquals(4,writes);assertEquals(0,cache.size)
        assertNull(ContextWaitInput.create(0,100,TimeRange(0,1000),1000,"not-a-random-id",emptyList()))
    }
}
