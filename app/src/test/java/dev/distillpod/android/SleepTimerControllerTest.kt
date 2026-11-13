package dev.distillpod.android

import android.os.Bundle
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import dev.distillpod.android.playback.SleepTimerCommands
import dev.distillpod.android.playback.SleepTimerController
import dev.distillpod.android.playback.SleepTimerPlayback
import dev.distillpod.core.SleepTimerStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepTimerControllerTest {
    private class FakePlayback : SleepTimerPlayback {
        override var isPlaying = true
        override var hasItem = true
        override var itemRemainingMs: Long? = 3_600_000L
        override var volume = 1f
        override var pauseAtEndOfItem = false
        val volumes = mutableListOf<Float>()
        var pauses = 0
        override fun applyVolume(value: Float) { volume = value; volumes += value }
        override fun pause() { pauses++; isPlaying = false }
    }

    private class Fixture(val scope: TestScope = TestScope()) {
        val playback = FakePlayback()
        val published = mutableListOf<SleepTimerStatus?>()
        val expired = mutableListOf<Boolean>()
        val controller = SleepTimerController(playback, scope, clock = { scope.testScheduler.currentTime },
            publish = { published += it }, onExpired = { expired += it }, tickMs = 250)
        val status get() = published.lastOrNull()
        fun advance(ms: Long) { scope.advanceTimeBy(ms); scope.runCurrent() }
        /** Mirrors the service listener: the player reports pause after [SleepTimerPlayback.pause]. */
        fun setPlaying(value: Boolean) { playback.isPlaying = value; controller.playingChanged() }
    }

    @Test fun `countdown fades, pauses through the player once and restores volume`() {
        val f = Fixture()
        f.controller.start(60_000)
        assertEquals(60_000L, f.status!!.remainingMs)
        assertTrue(f.status!!.counting)
        f.advance(44_000)
        assertTrue(f.playback.volumes.isEmpty() || f.playback.volumes.all { it == 1f })
        f.advance(10_000)
        assertTrue("The final window fades", f.playback.volume < 1f)
        f.advance(6_500)
        assertEquals(1, f.playback.pauses)
        assertEquals("Volume is restored after the pause", 1f, f.playback.volume, 0f)
        assertNull(f.status)
        assertEquals(listOf(false), f.expired)
        f.advance(120_000)
        assertEquals(1, f.playback.pauses)
    }

    @Test fun `manual pause freezes the countdown and resume continues it`() {
        val f = Fixture()
        f.controller.start(60_000)
        f.advance(20_000)
        f.setPlaying(false)
        val paused = f.status!!
        assertFalse(paused.counting)
        assertEquals(40_000L, paused.remainingMs)
        f.advance(10 * 60_000L)
        assertEquals(0, f.playback.pauses)
        f.setPlaying(true)
        assertTrue(f.status!!.counting)
        assertEquals(40_000L, f.status!!.remainingMs)
        f.advance(40_500)
        assertEquals(1, f.playback.pauses)
    }

    @Test fun `cancel during the fade restores volume and stops the timer`() {
        val f = Fixture()
        f.controller.start(20_000)
        f.advance(15_000)
        assertTrue(f.playback.volume < 1f)
        f.controller.cancel()
        assertEquals(1f, f.playback.volume, 0f)
        assertNull(f.status)
        f.advance(60_000)
        assertEquals(0, f.playback.pauses)
        assertTrue(f.expired.isEmpty())
    }

    @Test fun `extend during the fade restores volume and republishes the longer countdown`() {
        val f = Fixture()
        f.controller.start(20_000)
        f.advance(15_000)
        assertTrue(f.playback.volume < 1f)
        assertTrue(f.controller.extend())
        assertEquals(1f, f.playback.volume, 0f)
        assertTrue(f.status!!.remainingMs > 5 * 60_000L - 1_000)
        f.advance(60_000)
        assertEquals(0, f.playback.pauses)
    }

    @Test fun `end of episode pauses at the item end, fades on its remaining time and reports completion`() {
        val f = Fixture()
        f.controller.startEndOfEpisode()
        assertTrue(f.playback.pauseAtEndOfItem)
        assertTrue(f.status!!.endOfEpisode)
        assertFalse(f.controller.extend())
        f.playback.itemRemainingMs = 5_000
        f.advance(500)
        assertTrue(f.playback.volume < 1f)
        // The player paused itself at the end of the item.
        f.playback.isPlaying = false
        f.controller.itemEnded()
        assertEquals(listOf(true), f.expired)
        assertFalse(f.playback.pauseAtEndOfItem)
        assertEquals(1f, f.playback.volume, 0f)
        assertNull(f.status)
    }

    @Test fun `an item end without end of episode is ignored`() {
        val f = Fixture()
        f.controller.start(60_000)
        f.controller.itemEnded()
        assertTrue(f.expired.isEmpty())
        assertNotNull(f.status)
    }

    @Test fun `the queue ending cancels a countdown instead of leaving it frozen`() {
        val f = Fixture()
        f.controller.start(60_000)
        f.setPlaying(false)
        f.controller.itemEnded(queueEnded = true)
        assertNull(f.status)
        assertTrue(f.expired.isEmpty())
        assertEquals(0, f.playback.pauses)
    }

    @Test fun `the queue ending finishes end of episode`() {
        val f = Fixture()
        f.controller.startEndOfEpisode()
        f.controller.itemEnded(queueEnded = true)
        assertEquals(listOf(true), f.expired)
    }

    @Test fun `resuming inside the end of episode fade applies its volume at once`() {
        val f = Fixture()
        f.playback.isPlaying = false
        f.controller.startEndOfEpisode()
        f.playback.itemRemainingMs = 3_000
        f.setPlaying(true)
        assertTrue(f.playback.volume < 1f)
    }

    @Test fun `item change keeps the timer and an empty queue cancels it`() {
        val f = Fixture()
        f.controller.startEndOfEpisode()
        f.controller.itemChanged()
        assertTrue(f.playback.pauseAtEndOfItem)
        assertTrue(f.status!!.endOfEpisode)
        f.playback.hasItem = false
        f.controller.itemChanged()
        assertNull(f.status)
        assertFalse(f.playback.pauseAtEndOfItem)
    }

    @Test fun `skipping to another item during an end of episode fade restores volume at once`() {
        val f = Fixture()
        f.controller.startEndOfEpisode()
        f.playback.itemRemainingMs = 5_000
        f.advance(250)
        assertTrue(f.playback.volume < 1f)
        f.setPlaying(false)
        f.playback.itemRemainingMs = 1_800_000
        f.controller.itemChanged()
        assertEquals(1f, f.playback.volume, 0f)
        assertTrue(f.status!!.endOfEpisode)
    }

    @Test fun `switching from end of episode to a countdown stops pausing at the item end`() {
        val f = Fixture()
        f.controller.startEndOfEpisode()
        f.controller.start(60_000)
        assertFalse(f.playback.pauseAtEndOfItem)
        assertFalse(f.status!!.endOfEpisode)
    }

    @Test fun `release drops the timer without pausing`() {
        val f = Fixture()
        f.controller.start(20_000)
        f.advance(15_000)
        f.controller.release()
        assertNull(f.status)
        assertEquals(1f, f.playback.volume, 0f)
        f.advance(60_000)
        assertEquals(0, f.playback.pauses)
    }

    @Test fun `session commands validate their arguments and need a current item`() {
        val f = Fixture()
        fun send(command: androidx.media3.session.SessionCommand, args: Bundle = Bundle.EMPTY) = f.controller.command(command, args).resultCode
        assertEquals(SessionError.ERROR_BAD_VALUE, send(SleepTimerCommands.START))
        assertEquals(SessionError.ERROR_BAD_VALUE, send(SleepTimerCommands.START, SleepTimerCommands.duration(0)))
        assertEquals(SessionError.ERROR_BAD_VALUE, send(SleepTimerCommands.START, SleepTimerCommands.duration(SleepTimerCommands.MAX_DURATION_MS + 1)))
        assertEquals(SessionError.ERROR_INVALID_STATE, send(SleepTimerCommands.EXTEND))
        assertEquals(SessionResult.RESULT_SUCCESS, send(SleepTimerCommands.START, SleepTimerCommands.duration(15 * 60_000L)))
        assertEquals(15 * 60_000L, f.status!!.remainingMs)
        assertEquals(SessionResult.RESULT_SUCCESS, send(SleepTimerCommands.EXTEND))
        assertEquals(20 * 60_000L, f.status!!.remainingMs)
        assertEquals(SessionResult.RESULT_SUCCESS, send(SleepTimerCommands.END_OF_EPISODE))
        assertTrue(f.status!!.endOfEpisode)
        assertEquals(SessionResult.RESULT_SUCCESS, send(SleepTimerCommands.CANCEL))
        assertNull(f.status)
        f.playback.hasItem = false
        assertEquals(SessionError.ERROR_INVALID_STATE, send(SleepTimerCommands.START, SleepTimerCommands.duration(60_000)))
        assertEquals(SessionError.ERROR_INVALID_STATE, send(SleepTimerCommands.END_OF_EPISODE))
        assertEquals(SessionResult.RESULT_SUCCESS, send(SleepTimerCommands.CANCEL))
    }
}
