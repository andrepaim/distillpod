package dev.distillpod.android

import dev.distillpod.core.openToken
import java.security.InvalidKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class TokenVaultKeyRecoveryTest {
    private val broken: SecretKey = SecretKeySpec(ByteArray(7), "AES")
    private val working: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun `a key that can no longer encrypt is replaced when the stored credential is unreadable`() {
        var current = broken; var deleted = 0
        val sealed = sealReplacing("fresh", { current }, existingReadable = { false }, deleteKey = { deleted++; current = working })
        assertEquals(1, deleted)
        assertEquals("fresh", openToken(sealed, working))
    }

    @Test fun `a readable credential is never discarded to recover a key`() {
        var deleted = 0
        assertThrows(InvalidKeyException::class.java) {
            sealReplacing("fresh", { broken }, existingReadable = { true }, deleteKey = { deleted++ })
        }
        assertEquals(0, deleted)
    }

    @Test fun `a working key is kept`() {
        val sealed = sealReplacing("fresh", { working }, existingReadable = { error("not consulted") }, deleteKey = { error("not deleted") })
        assertEquals("fresh", openToken(sealed, working))
    }
}
