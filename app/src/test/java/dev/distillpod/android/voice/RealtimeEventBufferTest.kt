package dev.distillpod.android.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RealtimeEventBufferTest {
    @Test fun `budget is released as messages are consumed`() = runBlocking {
        val buffer = RealtimeEventBuffer(maxCharacters = 10)
        assertTrue(buffer.offer("123456"))
        assertEquals("123456", buffer.receive())
        assertTrue(buffer.offer("abcdef"))
        assertEquals("abcdef", buffer.receive())
        buffer.cancel()
    }
    @Test fun `large payloads fail closed without discarding earlier events`() = runBlocking {
        val buffer = RealtimeEventBuffer(maxCharacters = 10)
        assertTrue(buffer.offer("123456"))
        assertFalse(buffer.offer("78901"))
        assertEquals("123456", buffer.receive())
        val error = runCatching { buffer.receive() }.exceptionOrNull()
        assertEquals("Voice response exceeded buffer limit", error?.message)
    }
    @Test fun `tiny message floods are bounded independently of payload size`() = runBlocking {
        val buffer = RealtimeEventBuffer(maxCharacters = 100, maxEvents = 2)
        assertTrue(buffer.offer("a")); assertTrue(buffer.offer("b")); assertFalse(buffer.offer("c"))
        assertEquals("a", buffer.receive()); assertEquals("b", buffer.receive())
        assertTrue(runCatching { buffer.receive() }.isFailure)
    }
}
