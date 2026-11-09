package dev.distillpod.android.voice

import dev.distillpod.android.data.SegmentEntity
import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.data.TranscriptEntity
import org.junit.Assert.*
import org.junit.Test

class VoiceContextTest {
    private fun span(start: Long, end: Long, text: String = "speech") = SegmentEntity("ep", "rev", start, end, text)
    private val episode = EpisodeEntity("ep", "pod", "guid", "Private title", "https://example.com", mediaFingerprint = "audio")
    private val transcript = TranscriptEntity("ep", "rev", "audio", "model", "en", state = "partial")
    @Test fun `readiness explains unavailable context without including source content`() {
        val spans = listOf(span(1000, 9000))
        assertEquals(VoiceReadiness.MISSING_TRANSCRIPT, VoiceContext.readiness(episode, null, 10000, spans))
        assertEquals(VoiceReadiness.STALE_AUDIO, VoiceContext.readiness(episode, transcript.copy(fingerprint = "other"), 10000, spans))
        assertEquals(VoiceReadiness.STALE_AUDIO, VoiceContext.readiness(episode, transcript.copy(state = "stale"), 10000, spans))
        assertEquals(VoiceReadiness.ONLINE_ONLY, VoiceContext.readiness(episode, transcript.copy(source = "online"), 10000, spans))
        assertEquals(VoiceReadiness.EMPTY_SELECTION, VoiceContext.readiness(episode, transcript, 10000, listOf(span(9000, 11000))))
        assertEquals(VoiceReadiness.COVERAGE_GAP, VoiceContext.readiness(episode, transcript, 25001, spans))
        assertNull(VoiceContext.readiness(episode, transcript, 24000, spans))
        assertNull(VoiceContext.readiness(episode, transcript, 10000, spans))
        VoiceReadiness.entries.forEach { assertFalse(it.feedback.contains(episode.title)) }
    }
    @Test fun `all earlier speech is kept while future and boundary straddling segments are excluded`() {
        val selected = VoiceContext.select(180000, listOf(span(0, 1000), span(59000,61000), span(60000,70000), span(179000,181000), span(180000,190000)))
        assertEquals(listOf(0L, 59000L, 60000L), selected.map { it.startMs })
    }
    @Test fun `long passages are retained without a character budget`() {
        val selected = VoiceContext.select(10000, listOf(span(0,1000,"a".repeat(8000)), span(2000,3000,"b".repeat(8000))))
        assertEquals(listOf(0L, 2000L), selected.map { it.startMs })
        assertEquals(16000, selected.sumOf { it.text.length })
    }
    @Test fun `hours of transcript retain the beginning middle and current moment in chronological order`() {
        val segments = (0 until 1080).map { index ->
            span(index * 10000L, (index + 1) * 10000L, "Passage $index: " + "explicação 🧠 ".repeat(30))
        }
        assertEquals(segments, VoiceContext.select(10_800_000, segments.reversed()))
    }
    @Test fun `malformed timestamps never become listened context`() {
        val valid = span(0,1000)
        assertEquals(listOf(valid), VoiceContext.select(1000, listOf(span(-100,100), span(500,500), span(800,400), valid)))
    }
    @Test fun `microphone meter measures actual PCM amplitude`() {
        assertEquals(0f, microphoneLevel(ByteArray(960)), .0001f)
        assertEquals(0f, microphoneLevel(byteArrayOf()), .0001f)
        assertEquals(1f, microphoneLevel(byteArrayOf(-1,127,-1,127)), .0001f)
        val quiet = microphoneLevel(byteArrayOf(0,1,0,1))
        assertTrue(quiet > 0f && quiet < .1f)
    }
    @Test fun `resampling preserves continuity across chunk boundaries`() {
        val samples = ShortArray(1000) { (it * 20 - 10000).toShort() }
        val bytes = samples.flatMap { listOf(it.toByte(), (it.toInt() shr 8).toByte()) }.toByteArray()
        val all = PcmResampler().convert(bytes)
        val stream = PcmResampler()
        assertArrayEquals(all, stream.convert(bytes.copyOfRange(0,514)) + stream.convert(bytes.copyOfRange(514,bytes.size)))
        assertTrue(all.size in 2996..3000)
    }
}
