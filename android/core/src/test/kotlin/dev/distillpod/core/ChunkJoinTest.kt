package dev.distillpod.core
import org.junit.Assert.*
import org.junit.Test
class ChunkJoinTest {
    @Test fun `repeated overlap is removed across chunk boundaries including Portuguese`() {
        assertEquals("novas habilidades.", trimRepeatedOverlap("Praticar todos os dias desenvolve", "DIAS desenvolve novas habilidades."))
        assertEquals("build lasting skills.", trimRepeatedOverlap("small daily routines help", "routines help build lasting skills."))
    }
    @Test fun `single repeated words are retained to avoid deleting intentional repetition`() {
        assertEquals("very useful", trimRepeatedOverlap("that is very", "very useful"))
        assertEquals("different content", trimRepeatedOverlap("some prior content", "different content"))
    }
}
