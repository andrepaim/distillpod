package dev.distillpod.android

import dev.distillpod.android.data.SegmentEntity
import dev.distillpod.android.data.TranscriptStage
import dev.distillpod.android.features.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #203 phase 2: the transcript reads as paragraphs while segments keep their own timing. */
class TranscriptParagraphsTest {
    private fun seg(start: Long, end: Long, text: String = "word", ordinal: Int = 0) = SegmentEntity("e", "r", start, end, text, ordinal)

    @Test fun `close segments join one paragraph and keep their own times`() {
        val segments = listOf(seg(0, 2_000, "One."), seg(2_500, 4_000, "Two."), seg(5_200, 7_000, "Three."))
        val paragraphs = transcriptParagraphs(segments)
        assertEquals(1, paragraphs.size)
        assertEquals("One. Two. Three.", paragraphs[0].text)
        assertEquals(segments, paragraphs[0].segments)
        assertEquals(0L, paragraphs[0].startMs)
        assertEquals(7_000L, paragraphs[0].endMs)
    }

    @Test fun `a pause over 1_2 seconds starts a new paragraph, exactly 1_2 does not`() {
        val segments = listOf(seg(0, 1_000), seg(2_200, 3_000), seg(4_201, 5_000))
        val paragraphs = transcriptParagraphs(segments)
        assertEquals(listOf(0, 2), paragraphs.map { it.firstSegment })
        assertEquals(listOf(2, 1), paragraphs.map { it.segments.size })
    }

    @Test fun `a speaker change starts a paragraph only when speakers are known`() {
        val segments = listOf(seg(0, 1_000, "a", 0), seg(1_000, 2_000, "b", 1), seg(2_000, 3_000, "c", 2))
        assertEquals(1, transcriptParagraphs(segments).size)
        val speakers = mapOf(0 to "Ana", 1 to "Ana", 2 to "Bo")
        assertEquals(listOf(0, 2), transcriptParagraphs(segments, speaker = { speakers[it.ordinal] }).map { it.firstSegment })
    }

    @Test fun `paragraphs stop at about 320 characters without splitting a segment`() {
        val sentence = "x".repeat(99)
        val segments = (0 until 7).map { seg(it * 1_000L, it * 1_000L + 1_000, sentence, it) }
        val paragraphs = transcriptParagraphs(segments)
        // 3 x 99 + 2 spaces = 299; a fourth would make 399.
        assertEquals(listOf(3, 3, 1), paragraphs.map { it.segments.size })
        assertEquals(listOf(0, 3, 6), paragraphs.map { it.firstSegment })
        val long = listOf(seg(0, 1_000, "y".repeat(500)), seg(1_000, 2_000, "z"))
        assertEquals(listOf(1, 1), transcriptParagraphs(long).map { it.segments.size })
    }

    @Test fun `overlapping segments stay together and an empty transcript has no paragraphs`() {
        assertEquals(1, transcriptParagraphs(listOf(seg(0, 20_000), seg(5_000, 8_000))).size)
        assertEquals(emptyList<TranscriptParagraph>(), transcriptParagraphs(emptyList()))
    }

    @Test fun `the 179 focus selects the paragraph containing the target segment`() {
        val segments = listOf(seg(0, 9_000), seg(10_000, 19_000), seg(30_000, 39_000), seg(39_500, 45_000))
        val paragraphs = transcriptParagraphs(segments)
        assertEquals(listOf(0, 2), paragraphs.map { it.firstSegment })
        assertEquals(1, paragraphOfSegment(paragraphs, transcriptFocusIndex(segments, 40_000)))
        // Between passages the last one before the target is focused: segment 1, inside paragraph 0.
        assertEquals(0, paragraphOfSegment(paragraphs, transcriptFocusIndex(segments, 25_000)))
        assertEquals(-1, paragraphOfSegment(paragraphs, -1))
        assertEquals(-1, paragraphOfSegment(emptyList(), 0))
    }

    @Test fun `the current paragraph lasts until the next starts and ends with the last segment`() {
        val paragraphs = transcriptParagraphs(listOf(seg(1_000, 9_000), seg(10_000, 19_000), seg(30_000, 39_000)))
        assertEquals(-1, currentParagraphIndex(paragraphs, 500))
        assertEquals(0, currentParagraphIndex(paragraphs, 1_000))
        // The silence before the next paragraph still belongs to the one just heard.
        assertEquals(0, currentParagraphIndex(paragraphs, 25_000))
        assertEquals(1, currentParagraphIndex(paragraphs, 30_000))
        assertEquals(1, currentParagraphIndex(paragraphs, 38_999))
        assertEquals(-1, currentParagraphIndex(paragraphs, 39_000))
        assertEquals(-1, currentParagraphIndex(emptyList(), 0))
    }

    @Test fun `search finds paragraphs ignoring case and a blank query finds none`() {
        val paragraphs = transcriptParagraphs(listOf(seg(0, 1_000, "Attention matters."), seg(5_000, 6_000, "Curiosity too."),
            seg(9_000, 10_000, "More curiosity.")))
        assertEquals(listOf(1, 2), transcriptMatches(paragraphs, " CURIOSITY "))
        assertEquals(emptyList<Int>(), transcriptMatches(paragraphs, "  "))
        assertEquals(emptyList<Int>(), transcriptMatches(paragraphs, "zzzz"))
    }

    @Test fun `a saved moment marks the segments its range overlaps`() {
        val ranges = listOf(Triple("m1", 30_000L, 90_000L))
        assertEquals("m1", momentForSegment(seg(60_000, 70_000), ranges))
        assertEquals("m1", momentForSegment(seg(20_000, 30_001), ranges))
        assertNull(momentForSegment(seg(0, 30_000), ranges))
        assertNull(momentForSegment(seg(90_000, 95_000), ranges))
    }

    @Test fun `the episode page transcript row shortens the preparation state`() {
        fun status(state: String?, source: String? = null, stage: TranscriptStage? = null, percent: Int = 0, download: Int? = null, onPlay: Boolean = false) =
            transcriptShortStatus(state, source, stage, percent, download, onPlay)
        assertEquals("Ready · publisher", status("ready", "publisher"))
        assertEquals("Ready · online", status("ready", "online"))
        assertEquals("Ready · on this phone", status("ready", "whisper"))
        assertEquals("Transcribing · 42%", status("processing", stage = TranscriptStage.TRANSCRIBING, percent = 42))
        assertEquals("Downloading audio · 7%", status("processing", stage = TranscriptStage.DOWNLOADING, download = 7))
        assertEquals("Downloading audio", status("processing", stage = TranscriptStage.DOWNLOADING))
        assertEquals("Paused · progress saved", status("paused"))
        assertEquals("Needs a transcription model", status("waiting_model"))
        assertEquals("Not prepared yet", status(null))
        assertEquals("Prepared when you play", status(null, onPlay = true))
    }
}
