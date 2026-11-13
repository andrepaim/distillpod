package dev.distillpod.core.sync

import org.junit.Assert.*
import org.junit.Test

class MediaLocatorTest {
    private fun accepted(audioUrl: String) = runCatching {
        SyncProtocol.validate(SyncMutation("operation1", SyncOwner("userA", "generation1"), "identity", 0, mapOf("audioUrl" to audioUrl)))
    }.isSuccess

    @Test fun `a plain public address is unchanged`() {
        for (url in listOf("https://cdn.example.test/show/episode.mp3", "http://example.test/a%20b.mp3", "https://example.test"))
            assertEquals(url, MediaLocator.portable(url))
    }
    @Test fun `query strings and fragments never leave the phone`() {
        val cases = mapOf(
            "https://cdn.example.test/episode.mp3?token=secret&expires=1" to "https://cdn.example.test/episode.mp3",
            "https://cdn.example.test/episode.mp3?X-Amz-Signature=abc#t=30" to "https://cdn.example.test/episode.mp3",
            "https://cdn.example.test/episode.mp3#t=30" to "https://cdn.example.test/episode.mp3",
            "https://cdn.example.test/episode.mp3?" to "https://cdn.example.test/episode.mp3",
            "https://cdn.example.test?mail=a@b.test" to "https://cdn.example.test",
        )
        for ((raw, expected) in cases) {
            val portable = MediaLocator.portable(raw)
            assertEquals(raw, expected, portable)
            assertFalse(raw, accepted(raw))
            assertTrue(raw, accepted(portable!!))
        }
    }
    @Test fun `credential-bearing, non-public and unparseable addresses stay local`() {
        for (url in listOf("https://user:password@example.test/a.mp3?x=1", "file:///storage/a.mp3", "content://media/1", "ftp://example.test/a.mp3",
                "https:///a.mp3?x=1", "not a url", "https://example.test/" + "a".repeat(2048) + "?x=1"))
            assertNull(url, MediaLocator.portable(url))
    }
    @Test fun `YouTube videos named by their query stay local`() {
        for (url in listOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "https://youtu.be/dQw4w9WgXcQ?t=5", "https://www.youtube.com/playlist?list=PL1234567890"))
            assertNull(url, MediaLocator.portable(url))
    }
}
