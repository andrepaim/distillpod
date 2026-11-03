package dev.distillpod.core

import org.junit.Assert.*
import org.junit.Test

class TranscriptionProgressTest {
    @Test fun `active progress removes overlap and adds only work beyond saved coverage`() {
        assertEquals(120000L, transcribingAudioMs(120000, 0, 120000, 240000, 118000, 240000, 0))
        assertEquals(179000L, transcribingAudioMs(120000, 0, 120000, 240000, 118000, 240000, 50))
        assertEquals(240000L, transcribingAudioMs(120000, 0, 120000, 240000, 118000, 240000, 100))
        assertEquals(180000L, transcribingAudioMs(180000, 60000, 120000, 240000, 118000, 240000, 25))
        assertEquals(240000L, transcribingAudioMs(180000, 60000, 120000, 240000, 118000, 240000, 100))
    }
    @Test fun `prioritized chunks report total work rather than their position in the episode`() {
        assertEquals(59000L, transcribingAudioMs(0, 0, 600000, 720000, 598000, 720000, 50))
        assertEquals(120000L, transcribingAudioMs(0, 0, 600000, 720000, 598000, 720000, 100))
    }
}
