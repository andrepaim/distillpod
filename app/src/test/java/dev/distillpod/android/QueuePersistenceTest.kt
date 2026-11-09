package dev.distillpod.android

import dev.distillpod.android.playback.*
import org.junit.Assert.*
import org.junit.Test

class QueuePersistenceTest {
    @Test fun `clear persists after supported external restoration despite stale controller baseline`() {
        val empty=emptyList<String>()
        val periodic=queuePersistenceDecision(empty,listOf("restored"),listOf("restored"),false,false,restored=true)
        assertFalse(periodic.write)
        val cleared=queuePersistenceDecision(periodic.baseline,emptyList(),listOf("restored"),false,true)
        assertTrue(cleared.write)
        assertEquals(emptyList<String>(),cleared.baseline)
    }
    @Test fun `startup and periodic snapshots cannot overwrite incoming queues`() {
        assertFalse(queuePersistenceDecision(null,emptyList(),listOf("incoming"),false,false).write)
        assertFalse(queuePersistenceDecision(listOf("old"),listOf("old"),listOf("incoming"),false,false).write)
    }
    @Test fun `import marker preserves temporary active item without echo on replacement or periodic snapshot`() {
        val known=listOf("active","imported")
        val imported=queuePersistenceDecision(emptyList<String>(),known,listOf("imported"),true,true)
        assertFalse(imported.write)
        assertFalse(queuePersistenceDecision(imported.baseline,known,listOf("imported"),false,true).write)
        assertFalse(queuePersistenceDecision(imported.baseline,known,listOf("imported"),false,false).write)
        assertTrue(queuePersistenceDecision(imported.baseline,emptyList(),listOf("imported"),false,true).write)
    }
    @Test fun `already persisted boundaries do not echo sync but actual edits persist once`() {
        val previous=emptyList<String>()
        val restored=queuePersistenceDecision(previous,listOf("a"),listOf("a"),false,true)
        assertFalse(restored.write)
        val moved=queuePersistenceDecision(restored.baseline,listOf("b","a"),listOf("a"),false,true)
        assertTrue(moved.write)
        assertFalse(queuePersistenceDecision(moved.baseline,listOf("b","a"),listOf("b","a"),false,true).write)
    }
    @Test fun `source-only boundary cannot overwrite a newer external projection`() {
        val imported=listOf("active","a")
        assertFalse(queuePersistenceDecision(imported,listOf("active","a"),listOf("new"),false,true).write)
    }

}
