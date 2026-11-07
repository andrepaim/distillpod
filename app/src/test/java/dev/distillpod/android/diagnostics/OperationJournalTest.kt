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
        journal.transition("transcription",job,"failed","internal")
        assertEquals("timeout",journal.latest().single().reason)
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
    @Test fun `revision ordering survives expiry reopening and consent revocation`() {
        val file=File(folder.root,"journal");var wall=1000L;val job=id()
        val first=OperationJournal(file,{wall})
        first.transition("transcription",job,"running")
        val original=first.latest().single().sequence
        wall+=73*60*60*1000L
        assertTrue(first.pending().isEmpty())
        first.transition("transcription",job,"completed")
        val afterExpiry=first.latest().single().sequence
        assertTrue(afterExpiry>original)
        first.clear();assertTrue(first.latest().isEmpty())
        val reopened=OperationJournal(file,{wall})
        reopened.transition("transcription",job,"failed")
        assertTrue(reopened.latest().single().sequence>afterExpiry)
    }
    @Test fun `earlier journal files without sequence metadata remain readable`() {
        val file=File(folder.root,"journal");val job=id()
        val first=OperationJournal(file);first.transition("voice",job,"running")
        // Earlier private candidates ended directly after the last row.
        val bytes=file.readBytes();file.writeBytes(bytes.copyOf(bytes.size-4))
        val upgraded=OperationJournal(file)
        upgraded.transition("voice",job,"completed")
        assertEquals("completed",upgraded.latest().single().state)
        assertTrue(upgraded.latest().single().sequence>first.latest().single().sequence)
        upgraded.clear()
        assertFalse(file.readText().contains(job))
    }
    @Test fun `late completion or failure cannot replace cancellation and an explicit retry can start`() {
        val journal=OperationJournal(File(folder.root,"journal"));val job=id()
        journal.transition("distillation",job,"running")
        journal.transition("distillation",job,"cancelled")
        journal.transition("distillation",job,"failed")
        journal.transition("distillation",job,"completed")
        assertEquals("cancelled",journal.latest().single().state)
        journal.transition("distillation",job,"running",attempt=id())
        journal.transition("distillation",job,"completed")
        assertEquals("completed",journal.latest().single().state)
    }
    @Test fun `recovery preserves expected wait reasons and records interrupted execution before requeue`() {
        val file=File(folder.root,"journal");val wait=id();val run=id()
        OperationJournal(file).apply {
            transition("distillation",wait,"waiting","waiting_quota")
            transition("transcription",run,"running")
        }
        val recovered=OperationJournal(file)
        recovered.recover {kind,_->if(kind=="distillation")"waiting" else "queued"}
        assertEquals("waiting_quota",recovered.latest().single {it.operation==wait}.reason)
        assertEquals("queued",recovered.latest().single {it.operation==run}.state)
        assertTrue(recovered.pending().any {it.operation==run && it.state=="interrupted"})
    }
    @Test(expected=IllegalArgumentException::class) fun `content cannot be an identifier`() {
        OperationJournal(File(folder.root,"journal")).transition("voice","private content","running")
    }
}
