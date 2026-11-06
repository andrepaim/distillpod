package dev.distillpod.android.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceApiKeyStoreTest {
    private val fixture = "sk-test-only-01234567890123456789"
    @Test fun `save trims key and clear removes credential`() {
        var stored: String? = null
        val store = VoiceApiKeyStore({ stored }, { stored = it })
        assertEquals(0L, store.revisions.value)
        assertFalse(store.hasKey())
        store.save("  $fixture\n")
        assertTrue(store.hasKey()); assertEquals(fixture, store.requireKey())
        assertEquals(1L, store.revisions.value)
        store.clear(); assertNull(stored); assertFalse(store.hasKey())
        assertEquals(2L, store.revisions.value)
    }
    @Test fun `invalid replacement preserves existing key and never echoes input`() {
        var stored: String? = fixture
        val store = VoiceApiKeyStore({ stored }, { stored = it })
        for (bad in listOf("", "not-a-key-secret", "sk-short", "$fixture\n$fixture", "sk-" + "x".repeat(600))) {
            val failure = runCatching { store.save(bad) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals("Enter a valid OpenAI API key", failure!!.message)
            assertEquals(fixture, stored)
        }
    }
    @Test fun `unreadable vault gives a sanitized setup error and can be cleared`() {
        var cleared = false
        val store = VoiceApiKeyStore({ error("provider detail $fixture") }, { cleared = it == null })
        assertFalse(store.hasKey())
        val error = runCatching { store.requireKey() }.exceptionOrNull()!!
        assertFalse(error.toString().contains(fixture)); assertNull(error.cause)
        store.clear(); assertTrue(cleared)
    }
}
