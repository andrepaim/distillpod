package dev.distillpod.core

import org.junit.Assert.*
import org.junit.Test

class SleepTimerTest {
    private fun volume(step: SleepTimerStep?) = (step as SleepTimerStep.Play).volume

    @Test fun `inactive timer never changes playback`() {
        val timer = SleepTimer()
        assertFalse(timer.active)
        assertNull(timer.advance(60_000, 1_000))
        assertFalse(timer.itemEnded())
        assertNull(timer.status(counting = true, nowMs = 5))
    }

    @Test fun `countdown consumes playing time, fades over the final window and then pauses once`() {
        val timer = SleepTimer(fadeMs = 15_000)
        timer.start(60_000)
        assertTrue(timer.active)
        assertEquals(1f, volume(timer.advance(40_000, null)), 0f)
        assertEquals(1f, volume(timer.advance(5_000, null)), 0f)
        // 15 s left: the fade starts at full volume and decreases monotonically.
        val early = volume(timer.advance(5_000, null))
        val late = volume(timer.advance(8_000, null))
        assertTrue(early in 0f..1f && late in 0f..1f && late < early)
        assertEquals(2_000L, timer.remainingMs)
        assertEquals(SleepTimerStep.Pause, timer.advance(2_500, null))
        assertFalse(timer.active)
        assertNull(timer.advance(1_000, null))
    }

    @Test fun `negative clock deltas are ignored`() {
        val timer = SleepTimer()
        timer.start(30_000)
        timer.advance(-5_000, null)
        assertEquals(30_000L, timer.remainingMs)
    }

    @Test fun `start rejects non-positive durations and restarting replaces the previous timer`() {
        val timer = SleepTimer()
        assertThrows(IllegalArgumentException::class.java) { timer.start(0) }
        timer.startEndOfEpisode()
        timer.start(10 * 60_000L)
        assertFalse(timer.endOfEpisode)
        assertEquals(600_000L, timer.remainingMs)
        timer.startEndOfEpisode()
        assertTrue(timer.endOfEpisode)
        assertEquals(0L, timer.remainingMs)
    }

    @Test fun `cancel clears either mode`() {
        val timer = SleepTimer()
        timer.start(60_000); timer.cancel()
        assertFalse(timer.active)
        timer.startEndOfEpisode(); timer.cancel()
        assertFalse(timer.active)
        assertFalse(timer.itemEnded())
    }

    @Test fun `extend adds time, leaves the fade and does not apply to end of episode`() {
        val timer = SleepTimer(fadeMs = 15_000)
        timer.start(20_000)
        assertTrue(volume(timer.advance(15_000, null)) < 1f)
        assertTrue(timer.extend())
        assertEquals(5_000L + SLEEP_TIMER_EXTENSION_MS, timer.remainingMs)
        assertEquals(1f, volume(timer.advance(1_000, null)), 0f)
        timer.startEndOfEpisode()
        assertFalse(timer.extend())
        assertTrue(timer.endOfEpisode)
        timer.cancel()
        assertFalse(timer.extend())
        assertFalse(timer.active)
    }

    @Test fun `end of episode fades on the episode's remaining time and pauses only when the item ends`() {
        val timer = SleepTimer(fadeMs = 15_000)
        timer.startEndOfEpisode()
        assertEquals(1f, volume(timer.advance(60_000, 600_000)), 0f)
        assertEquals(1f, volume(timer.advance(1_000, null)), 0f)
        val fading = volume(timer.advance(1_000, 7_500))
        assertTrue(fading > 0f && fading < 1f)
        // Seeking back out of the fade window restores full volume.
        assertEquals(1f, volume(timer.advance(1_000, 120_000)), 0f)
        assertEquals(0f, volume(timer.advance(1_000, 0)), 0f)
        assertTrue(timer.active)
        assertTrue(timer.itemEnded())
        assertFalse(timer.active)
    }

    @Test fun `countdown is not ended by an item ending`() {
        val timer = SleepTimer()
        timer.start(60_000)
        assertFalse(timer.itemEnded())
        assertTrue(timer.active)
    }

    @Test fun `item change keeps a countdown, retargets end of episode and an empty queue cancels`() {
        val timer = SleepTimer()
        timer.start(60_000)
        timer.currentItemChanged(present = true)
        assertEquals(60_000L, timer.remainingMs)
        timer.currentItemChanged(present = false)
        assertFalse(timer.active)

        timer.startEndOfEpisode()
        timer.currentItemChanged(present = true)
        assertTrue(timer.endOfEpisode)
        timer.currentItemChanged(present = false)
        assertFalse(timer.active)
    }

    @Test fun `status is anchored so presentation counts down without per-second updates`() {
        val timer = SleepTimer()
        timer.start(10 * 60_000L)
        val counting = timer.status(counting = true, nowMs = 1_000)!!
        assertFalse(counting.endOfEpisode)
        assertEquals(600_000L, counting.remainingAt(1_000))
        assertEquals(540_000L, counting.remainingAt(61_000))
        assertEquals(0L, counting.remainingAt(10_000_000))
        val paused = timer.status(counting = false, nowMs = 1_000)!!
        assertEquals(600_000L, paused.remainingAt(61_000))
        timer.startEndOfEpisode()
        assertTrue(timer.status(counting = true, nowMs = 0)!!.endOfEpisode)
    }

    @Test fun `fade curve spans full to silent`() {
        assertEquals(1f, sleepFadeVolume(20_000, 15_000), 0f)
        assertEquals(1f, sleepFadeVolume(15_000, 15_000), 0f)
        assertEquals(0f, sleepFadeVolume(0, 15_000), 0f)
        assertEquals(0f, sleepFadeVolume(-1, 15_000), 0f)
        val values = (15_000 downTo 0 step 1_000).map { sleepFadeVolume(it.toLong(), 15_000) }
        assertEquals(values.sortedDescending(), values)
    }

    @Test fun `offered durations match the product options`() {
        assertEquals(listOf(5, 10, 15, 30, 45, 60), SLEEP_TIMER_MINUTES)
        assertEquals(5 * 60_000L, SLEEP_TIMER_EXTENSION_MS)
        assertTrue(SLEEP_TIMER_FADE_MS in 10_000L..30_000L)
    }
}
