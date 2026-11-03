package dev.distillpod.core

import org.junit.Assert.*
import org.junit.Test

class TranscriptTest {
    private val transcript = Transcript("media-a", listOf(
        Word("before", 1000, 2000), Word("hello", 61000, 62000),
        Word("world", 119000, 120000), Word("future", 121000, 122000)
    ), listOf(TimeRange(0, 123000)))

    @Test fun `extracts preceding minute on original timeline`() {
        val excerpt = transcript.excerpt(120000)
        assertEquals(60000L, excerpt.range.startMs)
        assertEquals("hello world", excerpt.text)
        assertTrue(excerpt.complete)
    }
    @Test fun `beginning is clamped and silence is explicit`() {
        assertEquals(0L, transcript.excerpt(1000).range.startMs)
        assertEquals("", transcript.excerpt(500).text)
    }
    @Test fun `coverage holes are not presented as complete`() {
        val partial = transcript.copy(coverage = listOf(TimeRange(0, 70000), TimeRange(80000, 123000)))
        assertFalse(partial.excerpt(120000).complete)
    }
    @Test fun `ad ranges are clamped merged and replayable`() {
        val ranges = normalizeRanges(listOf(TimeRange(1000, 5000), TimeRange(4000, 8000), TimeRange(9000, 15000)), 10000)
        assertEquals(listOf(TimeRange(1000, 8000), TimeRange(9000, 10000)), ranges)
        assertEquals(8000L, skipTarget(2000, ranges, true))
        assertNull(skipTarget(2000, ranges, false))
        assertNull(skipTarget(8000, ranges, true))
    }
    @Test(expected = IllegalArgumentException::class) fun `rejects reversed word timing`() { Word("bad", 10, 5) }
    @Test(expected = IllegalArgumentException::class) fun `negative position rejected`() { transcript.excerpt(-1) }
    @Test fun `changed audio invalidates transcript`() { assertFalse(transcript.matchesMedia("media-b")) }
}
