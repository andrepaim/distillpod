package dev.distillpod.android.diagnostics

import org.junit.Test
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class OperationJournalTest {
    @get:Rule val folder=TemporaryFolder()
    private fun id()=UUID.randomUUID().toString()
    @Test fun `duplicate callbacks and retry deliveries keep stable identities`() {
        val file=File(folder.root,"journal");val job=id();var ticks=100L
        val journal=OperationJournal(file,{1000},{ticks})
        journal.transition("transcription",job,"running");ticks+=50
        journal.transition("transcription",job,"failed","timeout")
        journal.transition("transcription",job,"failed","timeout")
        assertEquals(2,journal.pending().size);assertEquals(50L,journal.latest().single().elapsed)
        val report=journal.pending().last();assertEquals(report.id,OperationJournal(file,{1000}).pending().last().id)
        journal.acknowledge(report.id);assertFalse(OperationJournal(file,{1000}).pending().any {it.id==report.id})
        journal.transition("transcription",job,"running");journal.transition("transcription",job,"completed")
        assertEquals(2,journal.pending().map {it.attempt}.distinct().size)
        journal.transition("transcription",job,"completed");assertEquals(1,journal.pending().count {it.state=="completed"})
    }
    @Test fun `restart reconciles persisted success and exposes unknown outcomes without invented timing`() {
        val file=File(folder.root,"journal");val saved=id();val missing=id()
        OperationJournal(file).apply {transition("transcription",saved,"running");transition("voice",missing,"running")}
        val reopened=OperationJournal(file)
        reopened.recover {kind,_->if(kind=="transcription")"completed" else null}
        assertEquals(setOf("completed","unknown"),reopened.latest().map {it.state}.toSet())
        assertTrue(reopened.latest().all {it.elapsed == -1L})
    }
    @Test fun `clock changes do not make duration negative and journal stays bounded`() {
        var wall=100000L;var ticks=10L
        val journal=OperationJournal(File(folder.root,"journal"),{wall},{ticks})
        val job=id();journal.transition("playback",job,"running");wall-=10000;ticks+=25
        journal.transition("playback",job,"completed");assertEquals(25L,journal.latest().single().elapsed)
        repeat(510){journal.transition("capture",id(),"completed")}
        assertTrue(journal.pending().size<=500);assertTrue(journal.dropped>0)
        journal.clear();assertTrue(journal.pending().isEmpty())
    }
    @Test(expected=IllegalArgumentException::class) fun `content cannot be an identifier`() {
        OperationJournal(File(folder.root,"journal")).transition("voice","private content","running")
    }
}
