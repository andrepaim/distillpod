package dev.distillpod.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackSpeedTest {
    @Test fun `steps move by a tenth and snap from values between steps`() {
        assertEquals(1.1f, PlaybackSpeeds.faster(1f), 0f)
        assertEquals(0.9f, PlaybackSpeeds.slower(1f), 0f)
        assertEquals("1.25 snaps up to the next tenth", 1.3f, PlaybackSpeeds.faster(1.25f), 0f)
        assertEquals("1.25 snaps down to the previous tenth", 1.2f, PlaybackSpeeds.slower(1.25f), 0f)
        assertEquals(1.8f, PlaybackSpeeds.faster(1.75f), 0f)
        // Binary float noise must not skip or repeat a step.
        var speed = PlaybackSpeeds.MIN
        val visited = mutableListOf(speed)
        while (speed < PlaybackSpeeds.MAX) { speed = PlaybackSpeeds.faster(speed); visited += speed }
        assertEquals(26, visited.size)
        assertEquals((5..30).map { it / 10f }, visited)
    }

    @Test fun `steps clamp at the supported bounds`() {
        assertEquals(3f, PlaybackSpeeds.faster(3f), 0f)
        assertEquals(3f, PlaybackSpeeds.faster(2.95f), 0f)
        assertEquals(0.5f, PlaybackSpeeds.slower(0.5f), 0f)
        assertEquals(0.5f, PlaybackSpeeds.slower(0.55f), 0f)
        assertFalse(PlaybackSpeeds.canSpeedUp(3f))
        assertTrue(PlaybackSpeeds.canSpeedUp(2.9f))
        assertFalse(PlaybackSpeeds.canSlowDown(0.5f))
        assertTrue(PlaybackSpeeds.canSlowDown(0.6f))
    }

    @Test fun `supported values are normalized and invalid ones rejected`() {
        assertEquals(1.3f, PlaybackSpeeds.normalize(1.3000001f)!!, 0f)
        assertEquals(1.25f, PlaybackSpeeds.normalize(1.25f)!!, 0f)
        assertNull(PlaybackSpeeds.normalize(0.4f))
        assertNull(PlaybackSpeeds.normalize(3.1f))
        assertNull(PlaybackSpeeds.normalize(Float.NaN))
        assertNull(PlaybackSpeeds.normalize(Float.POSITIVE_INFINITY))
    }

    @Test fun `labels are short and locale independent`() {
        assertEquals("1×", PlaybackSpeeds.label(1f))
        assertEquals("1.25×", PlaybackSpeeds.label(1.25f))
        assertEquals("1.3×", PlaybackSpeeds.label(1.3000001f))
        assertEquals("0.5×", PlaybackSpeeds.label(0.5f))
        assertEquals("3×", PlaybackSpeeds.label(3f))
        assertEquals(listOf(1f, 1.25f, 1.5f, 2f), PlaybackSpeeds.presets)
    }

    @Test fun `a show's own speed wins over the global speed`() {
        val prefs = SpeedPreferences(global = 1.2f, shows = mapOf("a" to 1.8f))
        assertEquals(1.8f, prefs.target("a")!!, 0f)
        assertEquals(1.2f, prefs.target("b")!!, 0f)
        assertEquals(1.2f, prefs.target(null)!!, 0f)
        assertNull("Before any choice the current speed is kept", SpeedPreferences().target("a"))
    }

    @Test fun `a chosen speed is remembered for the show that owns one, otherwise globally`() {
        val prefs = SpeedPreferences(global = 1.2f, shows = mapOf("a" to 1.8f))
        assertEquals(SpeedPreferences(1.2f, mapOf("a" to 2f)), prefs.chosen("a", 2f))
        assertEquals(SpeedPreferences(1.5f, mapOf("a" to 1.8f)), prefs.chosen("b", 1.5f))
        assertEquals(SpeedPreferences(1.5f, mapOf("a" to 1.8f)), prefs.chosen(null, 1.5f))
        assertSame("Unsupported values are ignored", prefs, prefs.chosen("a", 9f))
    }

    @Test fun `keeping and forgetting a show's speed`() {
        val kept = SpeedPreferences().keep("a", 1.7f)
        assertEquals("The first kept speed also seeds the unknown global speed", SpeedPreferences(1.7f, mapOf("a" to 1.7f)), kept)
        assertEquals(SpeedPreferences(1f, mapOf("a" to 1.7f, "b" to 2f)), SpeedPreferences(1f, mapOf("a" to 1.7f)).keep("b", 2f))
        assertEquals(SpeedPreferences(1.7f), kept.forget("a"))
        assertTrue(kept.owns("a"))
        assertFalse(kept.forget("a").owns("a"))
    }
}
