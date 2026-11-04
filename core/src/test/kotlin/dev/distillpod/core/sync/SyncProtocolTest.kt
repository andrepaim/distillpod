package dev.distillpod.core.sync

import org.junit.Assert.*
import org.junit.Test

class SyncProtocolTest {
    private val owner = SyncOwner("userA", "generation1")
    private fun mutation(base: Long = 0, deleted: Boolean = false, payload: Map<String,String> = mapOf("subscribed" to "true")) =
        SyncMutation("operation1", owner, "subscription1", base, payload, deleted)
    @Test fun `create increments from zero`() {
        val result = SyncProtocol.merge(mutation(), null) as MergeDecision.Apply
        assertEquals(1L, result.record.version)
    }
    @Test fun `edit increments expected version`() {
        val result = SyncProtocol.merge(mutation(3), RemoteRecord(3, emptyMap())) as MergeDecision.Apply
        assertEquals(4L, result.record.version)
    }
    @Test fun `stale edit preserves remote alternative`() {
        val remote = RemoteRecord(4, mapOf("positionMs" to "500"))
        assertEquals(MergeDecision.Conflict(remote), SyncProtocol.merge(mutation(3), remote))
    }
    @Test fun `missing base is a conflict`() {
        assertEquals(MergeDecision.Conflict(null), SyncProtocol.merge(mutation(5), null))
    }
    @Test fun `old device cannot resurrect deleted subscription`() {
        val tombstone = RemoteRecord(4, emptyMap(), true)
        assertEquals(MergeDecision.Conflict(tombstone), SyncProtocol.merge(mutation(), tombstone))
    }
    @Test fun `explicit re-follow references tombstone`() {
        assertFalse((SyncProtocol.merge(mutation(4), RemoteRecord(4, emptyMap(), true)) as MergeDecision.Apply).record.deleted)
    }
    @Test fun `deletion strips content`() {
        assertEquals(emptyMap<String,String>(), (SyncProtocol.merge(mutation(1, true), RemoteRecord(1, mapOf("excerpt" to "old"))) as MergeDecision.Apply).record.payload)
    }
    @Test fun `intentional rewind is not maximum position`() {
        val result = SyncProtocol.merge(mutation(2, payload=mapOf("positionMs" to "100")), RemoteRecord(2, mapOf("positionMs" to "1000"))) as MergeDecision.Apply
        assertEquals("100", result.record.payload["positionMs"])
    }
    @Test fun `same fields from stale session still conflict`() {
        assertTrue(SyncProtocol.merge(mutation(), RemoteRecord(1, mutation().payload)) is MergeDecision.Conflict)
    }
    @Test fun `invalid document path is rejected`() { invalid(mutation().copy(key="users/other")) }
    @Test fun `negative revision is rejected`() { invalid(mutation(-1)) }
    @Test fun `credential fields are rejected`() { invalid(mutation(payload=mapOf("accessToken" to "secret"))) }
    @Test fun `unknown payload fields are rejected`() { invalid(mutation(payload=mapOf("surprise" to "content"))) }
    @Test fun `private locators stay local`() { invalid(mutation(payload=mapOf("feedUrl" to "https://example.test/rss?token=secret"))) }
    @Test fun `public feed locator is allowed`() { SyncProtocol.validate(mutation(payload=mapOf("feedUrl" to "https://example.test/rss"))) }
    @Test fun `payload size is bounded in UTF8 bytes`() { invalid(mutation(payload=mapOf("excerpt" to "é".repeat(70000)))) }
    @Test fun `retry is bounded and uses supplied jitter`() {
        assertEquals(15000L, SyncProtocol.retryDelayMs(0, 0.0))
        assertEquals(30000L, SyncProtocol.retryDelayMs(0, 1.0))
        assertTrue(SyncProtocol.retryDelayMs(Int.MAX_VALUE, 1.0) <= 3600000L)
    }
    private fun invalid(mutation: SyncMutation) {
        try { SyncProtocol.validate(mutation); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }
}
