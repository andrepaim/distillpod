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
