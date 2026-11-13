package dev.distillpod.android

import dev.distillpod.android.playback.LoudnessEffect
import dev.distillpod.android.playback.SleepTimerController
import dev.distillpod.android.playback.SleepTimerPlayback
import dev.distillpod.android.playback.VOLUME_BOOST_GAIN_MB
import dev.distillpod.android.playback.VolumeBoost
import dev.distillpod.android.playback.VolumeBoostStatus
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
class VolumeBoostTest {
    private class FakeEffect(val session: Int) : LoudnessEffect {
        var gainMb: Int? = null
        var released = false
        override fun enable(gainMb: Int) { this.gainMb = gainMb }
        override fun release() { released = true }
    }

    private class Fixture(var failing: Boolean = false) {
        val created = mutableListOf<FakeEffect>()
        val failures = mutableListOf<Throwable>()
        val published = mutableListOf<VolumeBoostStatus>()
        val boost = VolumeBoost(create = { session ->
            if (failing) throw UnsupportedOperationException("Effect library not loaded")
            FakeEffect(session).also { created += it }
        }, onFailure = { failures += it }, publish = { published += it })
        val active get() = created.filter { !it.released }
    }

    @Test fun `off creates no effect`() {
        val f = Fixture()
        f.boost.sessionChanged(17)
        assertTrue(f.created.isEmpty())
        assertEquals(VolumeBoostStatus(), f.published.lastOrNull() ?: VolumeBoostStatus())
    }

    @Test fun `on attaches one effect with the boost gain to the player's session`() {
        val f = Fixture()
        f.boost.sessionChanged(17)
        f.boost.enabledChanged(true)
        assertEquals(1, f.active.size)
        assertEquals(17, f.active.single().session)
        assertEquals(VOLUME_BOOST_GAIN_MB, f.active.single().gainMb)
        assertEquals(VolumeBoostStatus(requested = true, session = 17), f.published.last())
        // Repeating the same state does not recreate it.
        f.boost.enabledChanged(true); f.boost.sessionChanged(17)
        assertEquals(1, f.created.size)
    }

    @Test fun `no effect is attached without a player session, never to the global output mix`() {
        val f = Fixture()
        f.boost.enabledChanged(true)
        f.boost.sessionChanged(0)
        assertTrue(f.created.isEmpty())
        f.boost.sessionChanged(23)
        assertEquals(23, f.active.single().session)
    }

    @Test fun `a new audio session moves the effect and turning it off releases it`() {
        val f = Fixture()
        f.boost.sessionChanged(17); f.boost.enabledChanged(true)
        f.boost.sessionChanged(31)
        assertTrue(f.created[0].released)
        assertEquals(31, f.active.single().session)
        f.boost.enabledChanged(false)
        assertTrue(f.active.isEmpty())
        assertEquals(VolumeBoostStatus(), f.published.last())
    }

    @Test fun `an unavailable effect is reported and playback setup continues`() {
        val f = Fixture(failing = true)
        f.boost.sessionChanged(17); f.boost.enabledChanged(true)
        assertEquals(1, f.failures.size)
        assertEquals(VolumeBoostStatus(requested = true, unavailable = true), f.published.last())
        f.failing = false
        f.boost.sessionChanged(18)
        assertEquals(18, f.active.single().session)
        assertFalse(f.published.last().unavailable)
    }

    @Test fun `release frees the effect and ignores later changes`() {
        val f = Fixture()
        f.boost.sessionChanged(17); f.boost.enabledChanged(true)
        f.boost.release()
        assertTrue(f.created.single().released)
        f.boost.sessionChanged(40); f.boost.enabledChanged(false); f.boost.enabledChanged(true)
        assertEquals(1, f.created.size)
    }

    /**
     * The sleep timer fades the player volume; the boost is a session effect with a fixed gain.
     * Neither touches the other: the boosted output still fades to the pause, the volume is then
     * restored, and the boost stays attached with the same gain.
     */
    @Test fun `the sleep timer fade composes with the boost`() {
        val scope = TestScope()
        val f = Fixture()
        f.boost.sessionChanged(17); f.boost.enabledChanged(true)
        val effect = f.active.single()
        val playback = object : SleepTimerPlayback {
            override var isPlaying = true
            override val hasItem = true
            override val itemRemainingMs: Long? = 3_600_000L
            override var volume = 1f
            override var pauseAtEndOfItem = false
            var pauses = 0
            override fun applyVolume(value: Float) { volume = value }
            override fun pause() { pauses++; isPlaying = false }
        }
        val timer = SleepTimerController(playback, scope, clock = { scope.testScheduler.currentTime },
            publish = {}, onExpired = {}, tickMs = 250)
        /** Linear output level: player volume times the boost's fixed gain. */
        fun output() = playback.volume * Math.pow(10.0, effect.gainMb!! / 2000.0).toFloat()
        val full = output()
        timer.start(20_000)
        val levels = mutableListOf<Float>()
        repeat(80) {
            scope.advanceTimeBy(250); scope.runCurrent()
            if (playback.isPlaying) levels += output()
            assertEquals("The fade never changes the boost", VOLUME_BOOST_GAIN_MB, effect.gainMb)
            assertFalse(effect.released)
        }
        assertEquals(1, playback.pauses)
        assertTrue("Boosted output is louder than unboosted", full > 1f)
        assertTrue("The boosted output fades", levels.last() < full / 2)
        assertEquals("The fade never rises", levels, levels.sortedDescending())
        assertEquals("Volume is restored after the pause", 1f, playback.volume, 0f)
        assertEquals(1, f.active.size)
    }
}
