package dev.distillpod.android.voice

import dev.distillpod.android.data.SegmentEntity
import org.junit.Assert.*
import org.junit.Test

class VoiceContextTest {
    private fun span(start: Long, end: Long, text: String = "speech") = SegmentEntity("ep", "rev", start, end, text)
    @Test fun `window excludes future speech and boundary straddling segments`() {
        val selected = VoiceContext.select(180000, listOf(span(0, 1000), span(59000,61000), span(60000,70000), span(179000,181000), span(180000,190000)))
        assertEquals(listOf(60000L), selected.map { it.startMs })
    }
    @Test fun `text budget favors recent speech in chronological order`() {
        val selected = VoiceContext.select(10000, listOf(span(0,1000,"a".repeat(8000)), span(2000,3000,"b".repeat(8000))))
        assertEquals(listOf(2000L), selected.map { it.startMs })
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
