package dev.distillpod.core
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class TokenEnvelopeTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    @Test fun `credentials round trip without storing plaintext`() {
        val encrypted = sealToken("private-refresh-token", key)
        assertFalse(String(encrypted).contains("private-refresh-token"))
        assertEquals("private-refresh-token", openToken(encrypted, key))
        assertFalse(encrypted.contentEquals(sealToken("private-refresh-token", key)))
    }
    @Test fun `tampering and wrong key fail authentication`() {
        val encrypted = sealToken("secret", key)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { openToken(encrypted, key) }
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertThrows(Exception::class.java) { openToken(sealToken("secret", key), other) }
    }
}
