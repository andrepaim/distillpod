package dev.distillpod.core

import org.junit.Assert.*
import org.junit.Test

class MomentContextTest {
    @Test fun `context is bounded and clamps at episode boundaries`() {
        assertEquals(TimeRange(0,25_000),MomentContexts.range(10_000,100_000))
        assertEquals(TimeRange(80_000,215_000),MomentContexts.range(200_000,500_000))
        assertEquals(TimeRange(20_000,215_000),MomentContexts.range(200_000,500_000,true))
        assertEquals(TimeRange(0,100_000),MomentContexts.range(100_000,100_000))
    }
    @Test fun `context can be extended forward where an idea continues after the tap`() {
        assertEquals(TimeRange(80_000,275_000),MomentContexts.range(200_000,500_000,ContextWindow.LATER))
        assertEquals(TimeRange(20_000,215_000),MomentContexts.range(200_000,500_000,ContextWindow.EARLIER))
        assertEquals(MomentContexts.range(200_000,500_000),MomentContexts.range(200_000,500_000,ContextWindow.STANDARD))
        // Clamped to the episode end and start; an unknown duration keeps the full forward reach.
        assertEquals(TimeRange(130_000,260_000),MomentContexts.range(250_000,260_000,ContextWindow.LATER))
        assertEquals(TimeRange(0,85_000),MomentContexts.range(10_000,0,ContextWindow.LATER))
        // Near the start an earlier window adds nothing: callers detect the identical input.
        assertEquals(MomentContexts.range(60_000,500_000),MomentContexts.range(60_000,500_000,ContextWindow.EARLIER))
    }
    @Test fun `every window fits the frozen context limit and the published passage bound`() {
        for (window in ContextWindow.values()) {
            val range=MomentContexts.range(1_000_000,0,window)
            assertTrue(range.endMs-range.startMs<=MomentContexts.MAX_WINDOW_MS)
            assertTrue(range.endMs-1_000_000<=MomentContexts.MAX_FORWARD_MS)
            assertTrue(1_000_000-range.startMs<=MomentContexts.MAX_BACKWARD_MS)
        }
        assertEquals(ContextWindow.LATER,ContextWindow.of("later",false))
        assertEquals(ContextWindow.EARLIER,ContextWindow.of(null,true))
        assertEquals(ContextWindow.STANDARD,ContextWindow.of(null,false))
        assertEquals(ContextWindow.STANDARD,ContextWindow.of("unknown",false))
    }
    @Test fun `candidate sentences preserve original Portuguese and segment timing`() {
        val context=MomentContexts.build(TimeRange(0,10_000),TimeRange(0,25_000), listOf(
            SubtitleSegment(TimeRange(1000,9000),"Se faltar energia, a obra pode parar. Isso é uma hipótese.")))
        assertEquals(2,context.spans.size)
        assertEquals("Se faltar energia, a obra pode parar.",context.spans[0].text)
        assertEquals(1000L,context.passage(0,1).startMs)
        assertEquals("Se faltar energia, a obra pode parar. Isso é uma hipótese.",context.quote(context.passage(0,1),0,1))
    }
    @Test fun `quotes cannot reference text outside their selected passage`() {
        val context=MomentContexts.build(TimeRange(0,10_000),TimeRange(0,25_000),listOf(
            SubtitleSegment(TimeRange(0,9000),"First sentence. Second sentence.")))
        assertThrows(IllegalArgumentException::class.java) { context.quote(context.passage(1,1),0,1) }
        assertThrows(IllegalArgumentException::class.java) { context.passage(0,99) }
    }
    @Test fun `an unrelated earlier thought cannot become the distill`() {
        val context=MomentContexts.build(TimeRange(140_000,200_000),TimeRange(80_000,215_000),listOf(
            SubtitleSegment(TimeRange(90_000,110_000),"Unrelated early thought."),
            SubtitleSegment(TimeRange(170_000,190_000),"The thought the listener captured.")))
        assertThrows(IllegalArgumentException::class.java) { context.passage(0,0) }
        assertThrows(IllegalArgumentException::class.java) { context.passage(0,1) }
        assertEquals("The thought the listener captured.",context.passage(1,1).text)
    }
    @Test fun `boundary segments outside bounded context are never silently included`() {
        val context=MomentContexts.build(TimeRange(0,10_000),TimeRange(0,25_000),listOf(
            SubtitleSegment(TimeRange(0,9000),"Captured thought."),SubtitleSegment(TimeRange(20_000,30_000),"Next topic.")))
        assertEquals(listOf("Captured thought."),context.spans.map { it.text })
    }
    @Test fun `oversize sources fail instead of truncating`() {
        assertThrows(IllegalArgumentException::class.java) { MomentContexts.build(TimeRange(0,10_000),TimeRange(0,25_000),listOf(SubtitleSegment(TimeRange(0,9000),"a".repeat(25_000)))) }
    }
}
