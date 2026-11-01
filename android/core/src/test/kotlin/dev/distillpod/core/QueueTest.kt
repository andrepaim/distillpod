package dev.distillpod.core
import org.junit.Assert.*
import org.junit.Test

class QueueTest {
    @Test fun `adding next moves rather than duplicates`() {
        val q = EpisodeQueue(listOf("a", "b"))
        q.addNext("b"); assertEquals(listOf("b", "a"), q.items)
        q.addLast("b"); assertEquals(listOf("a", "b"), q.items)
    }
    @Test fun `failed playback retains queue item`() {
        val q = EpisodeQueue(listOf("a", "b"))
        assertEquals("a", q.peek()); assertEquals(listOf("a", "b"), q.items)
        q.acknowledgePlayed("a"); assertEquals(listOf("b"), q.items)
    }
    @Test fun `reordering validates indices without corrupting queue`() {
        val q = EpisodeQueue(listOf("a", "b", "c"))
        q.move(0, 2); assertEquals(listOf("b", "c", "a"), q.items)
        assertThrows(IllegalArgumentException::class.java) { q.move(-1, 0) }
        assertEquals(listOf("b", "c", "a"), q.items)
    }
    @Test fun `episode identity is scoped to feed`() {
        assertNotEquals(episodeId("feed-a", "guid"), episodeId("feed-b", "guid"))
        assertEquals(episodeId("feed-a", "guid"), episodeId("feed-a", "guid"))
    }
}
