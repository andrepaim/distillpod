package dev.distillpod.android

import dev.distillpod.android.data.ActiveFiles
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class FixtureAudioOwnershipTest {
    @Test fun canonicalLoaderLeaseBlocksCleanupThroughAnAlias() {
        val root = Files.createTempDirectory("fixture-ownership")
        try {
            val actual = Files.createDirectory(root.resolve("actual"))
            val alias = Files.createSymbolicLink(root.resolve("alias"), actual)
            val file = alias.resolve("audio.wav").toFile()
            file.writeText("fixture")
            val active = ActiveFiles()
            val lease = active.audioOwnership.acquire(active.audioOwnership.select(file.canonicalPath)!!)!!
            try {
                assertNotEquals(file.absolutePath, file.canonicalPath)
                assertFalse("The old raw-only wait misses this loader lease", active.busy(file.absolutePath))
                assertFalse("Canonical loader ownership must block fixture cleanup", fixtureAudioReleased(active, listOf(file)))
            } finally { lease.close() }
            assertTrue(fixtureAudioReleased(active, listOf(file)))
        } finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } } }
    }

    @Test fun partialOwnershipAlsoBlocksTheWholeFixture() {
        val root = Files.createTempDirectory("fixture-partial")
        try {
            val audio = root.resolve("audio.wav").toFile()
            val partial = root.resolve("audio.partial").toFile()
            val active = ActiveFiles()
            val lease = active.audioOwnership.acquire(active.audioOwnership.select(partial.canonicalPath)!!)!!
            try { assertFalse(fixtureAudioReleased(active, listOf(audio, partial))) } finally { lease.close() }
            assertTrue(fixtureAudioReleased(active, listOf(audio, partial)))
        } finally { Files.delete(root) }
    }

    @Test fun appPointersAndProcessingRemainProtected() {
        val file = java.io.File("fixture-processing.wav").absoluteFile
        val active = ActiveFiles()
        active.playback = file.path
        assertFalse(fixtureAudioReleased(active, listOf(file)))
        active.playback = null
        active.queued = setOf(file.path)
        assertFalse(fixtureAudioReleased(active, listOf(file)))
        active.queued = emptySet()
        active.acquire(file)
        try { assertFalse(fixtureAudioReleased(active, listOf(file))) } finally { active.release(file) }
        assertTrue(fixtureAudioReleased(active, listOf(file)))
    }
}
