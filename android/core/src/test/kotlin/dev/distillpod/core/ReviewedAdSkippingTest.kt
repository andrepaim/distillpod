package dev.distillpod.core

import org.junit.Assert.*
import org.junit.Test

class ReviewedAdSkippingTest {
    @Test fun `opt in boundaries and deliberate replay never loop`() {
        val skip = ReviewedAdSkipping()
        val ranges = listOf(TimeRange(1000, 4000), TimeRange(5000, 6000))
        assertNull(skip.target("a", 1000, ranges, false))
        assertEquals(4000L, skip.target("a", 1000, ranges, true))
        assertNull(skip.target("a", 4000, ranges, true))
        skip.seek("a", 1100, ranges)
        assertNull(skip.target("a", 1100, ranges, true))
        assertNull(skip.target("a", 3999, ranges, true))
        assertEquals(6000L, skip.target("a", 5000, ranges, true))
        assertEquals(4000L, skip.target("b", 1100, ranges, true))
    }
}
