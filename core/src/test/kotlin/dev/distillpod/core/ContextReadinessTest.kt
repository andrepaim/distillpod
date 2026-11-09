package dev.distillpod.core
import org.junit.Assert.*
import org.junit.Test

class ContextReadinessTest {
    @Test fun `coverage distinguishes missing partial and complete without claiming speech quality`() {
        val range=TimeRange(100,300)
        assertEquals(200L,ContextReadiness.missing(range,emptyList()))
        assertEquals(50L,ContextReadiness.missing(range,listOf(TimeRange(0,200),TimeRange(150,250))))
        assertEquals(0L,ContextReadiness.missing(range,listOf(TimeRange(0,400))))
    }
    @Test fun `boundary losses count both edges while frozen counts use exact builder spans`() {
        val evidence=ContextReadiness()
        val context=MomentContexts.build(TimeRange(100,200),TimeRange(100,300),listOf(
            SubtitleSegment(TimeRange(0,150),"left secret"),
            SubtitleSegment(TimeRange(250,350),"right secret"),
            SubtitleSegment(TimeRange(0,400),"both secret"),
            SubtitleSegment(TimeRange(120,180),"One. Two."),
            SubtitleSegment(TimeRange(180,200),"   ")),evidence::built)
        assertEquals(5,evidence.inputSegments)
        assertEquals(2,evidence.excludedLeft)
        assertEquals(2,evidence.excludedRight)
        assertEquals(context.spans.size,evidence.usableSpans)
        assertEquals(context.spans.sumOf {it.text.length+1}.toLong(),evidence.contextChars)
        assertEquals(1,evidence.usableSegments)
    }
    @Test fun `oversize evidence is captured before identical builder rejection`() {
        val evidence=ContextReadiness()
        assertThrows(IllegalArgumentException::class.java) {
            MomentContexts.build(TimeRange(0,10),TimeRange(0,20),
                listOf(SubtitleSegment(TimeRange(0,10),"x".repeat(24000))),evidence::built)
        }
        assertTrue(evidence.oversized)
        assertEquals(24001L,evidence.contextChars)
    }
    @Test fun `complete empty context and unknown duration preserve selection`() {
        val evidence=ContextReadiness()
        val range=MomentContexts.range(200000,0)
        assertEquals(TimeRange(80000,215000),range)
        val context=MomentContexts.build(TimeRange(140000,200000),range,
            listOf(SubtitleSegment(TimeRange(150000,160000)," ")),evidence::built)
        assertTrue(context.spans.isEmpty())
        assertEquals(0,evidence.usableSpans)
        assertFalse(evidence.oversized)
    }
}
