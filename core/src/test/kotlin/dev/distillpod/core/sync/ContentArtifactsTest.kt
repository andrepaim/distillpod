package dev.distillpod.core.sync

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class ContentArtifactsTest {
    @Test fun `large transcript content round trips without a Firestore document`() {
        val bytes="synthetic timed transcript segment\n".repeat(50000).toByteArray()
        val artifact=ContentArtifacts.encode(bytes)
        assertTrue(artifact.compressed.size<bytes.size)
        assertEquals(64,artifact.hash.length)
        assertArrayEquals(bytes,ContentArtifacts.decode(artifact))
    }
    @Test fun `wrong content hash is rejected before import`() {
        val artifact=ContentArtifacts.encode("saved transcript".toByteArray())
        assertThrows(IllegalArgumentException::class.java){ContentArtifacts.decode(artifact.copy(hash="0".repeat(64)))}
    }
    @Test fun `future format and wrong declared size are rejected`() {
        val artifact=ContentArtifacts.encode("saved transcript".toByteArray())
        assertThrows(IllegalArgumentException::class.java){ContentArtifacts.decode(artifact.copy(schema=2))}
        assertThrows(IllegalArgumentException::class.java){ContentArtifacts.decode(artifact.copy(expandedBytes=1))}
    }
    @Test fun `compressed payload cannot exceed its declared expansion budget`() {
        val out=ByteArrayOutputStream();GZIPOutputStream(out).use {it.write(ByteArray(2*1024*1024){65})}
        val artifact=ContentArtifact(1,"0".repeat(64),10,out.toByteArray())
        assertThrows(IllegalArgumentException::class.java){ContentArtifacts.decode(artifact)}
    }
}
