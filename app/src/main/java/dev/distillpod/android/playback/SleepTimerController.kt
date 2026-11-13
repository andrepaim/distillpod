package dev.distillpod.android.playback

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import dev.distillpod.core.SleepTimer
import dev.distillpod.core.SleepTimerStatus
import dev.distillpod.core.SleepTimerStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Same-application protocol for the service-owned sleep timer; never advertised to car or other external controllers. */
internal object SleepTimerCommands {
    const val DURATION_MS = "durationMs"
    const val MAX_DURATION_MS = 24 * 60 * 60_000L
    /** Starts a countdown of [DURATION_MS] playing time, replacing any running timer. */
    val START = SessionCommand("dev.distillpod.android.SLEEP_TIMER_START", Bundle.EMPTY)
    val END_OF_EPISODE = SessionCommand("dev.distillpod.android.SLEEP_TIMER_END_OF_EPISODE", Bundle.EMPTY)
    val EXTEND = SessionCommand("dev.distillpod.android.SLEEP_TIMER_EXTEND", Bundle.EMPTY)
    val CANCEL = SessionCommand("dev.distillpod.android.SLEEP_TIMER_CANCEL", Bundle.EMPTY)
    val all = setOf(START, END_OF_EPISODE, EXTEND, CANCEL)
    fun duration(ms: Long) = Bundle().apply { putLong(DURATION_MS, ms) }
}

/** The parts of the service player the sleep timer drives. */
internal interface SleepTimerPlayback {
    val isPlaying: Boolean
    val hasItem: Boolean
    /** The current item's remaining playing time at the current speed, when known. */
    val itemRemainingMs: Long?
    val volume: Float
    /** Pause at the end of the current item instead of advancing to the next one. */
    var pauseAtEndOfItem: Boolean
    fun applyVolume(value: Float)
    fun pause()
}

/** Adapts the service's player; [pauseAtEnd] reaches the ExoPlayer behind any forwarding wrapper. */
internal class PlayerSleepTimerPlayback(private val player: Player, private val pauseAtEnd: (Boolean) -> Unit) : SleepTimerPlayback {
    override val isPlaying get() = player.isPlaying
    override val hasItem get() = player.currentMediaItem != null
    override val itemRemainingMs: Long? get() {
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return null
        val speed = player.playbackParameters.speed.takeIf { it > 0f } ?: 1f
        return ((duration - player.currentPosition).coerceAtLeast(0) / speed).toLong()
    }
    override val volume get() = player.volume
    override var pauseAtEndOfItem = false
        set(value) { if (field != value) { field = value; pauseAtEnd(value) } }
    override fun applyVolume(value: Float) { player.volume = value }
    override fun pause() = player.pause()
}

/**
 * Owns the sleep timer inside the playback service, so it survives the activity. It is in memory
 * only: service or process death drops it. Expiry pauses through the player, so the ordinary
 * play/pause boundary checkpoint persists progress. [publish] receives anchored presentation state
 * on changes only; [onExpired] reports whether an end-of-episode timer finished its item.
 */
internal class SleepTimerController(
    private val playback: SleepTimerPlayback,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val publish: (SleepTimerStatus?) -> Unit,
    private val onExpired: (endOfEpisode: Boolean) -> Unit = {},
    private val tickMs: Long = 250,
) {
    private val timer = SleepTimer()
    private var ticking: Job? = null
    private var lastTick = 0L
    /** The volume before the fade lowered it; null when the timer has not changed the volume. */
    private var fadeBaseline: Float? = null

    fun command(command: SessionCommand, args: Bundle): SessionResult {
        fun result(code: Int) = SessionResult(code)
        return when (command) {
            SleepTimerCommands.START -> {
                val duration = args.getLong(SleepTimerCommands.DURATION_MS, 0)
                when {
                    duration <= 0 || duration > SleepTimerCommands.MAX_DURATION_MS -> result(SessionError.ERROR_BAD_VALUE)
                    !playback.hasItem -> result(SessionError.ERROR_INVALID_STATE)
                    else -> { start(duration); result(SessionResult.RESULT_SUCCESS) }
                }
            }
            SleepTimerCommands.END_OF_EPISODE ->
                if (!playback.hasItem) result(SessionError.ERROR_INVALID_STATE) else { startEndOfEpisode(); result(SessionResult.RESULT_SUCCESS) }
            SleepTimerCommands.EXTEND -> result(if (extend()) SessionResult.RESULT_SUCCESS else SessionError.ERROR_INVALID_STATE)
            SleepTimerCommands.CANCEL -> { cancel(); result(SessionResult.RESULT_SUCCESS) }
            else -> result(SessionError.ERROR_NOT_SUPPORTED)
        }
    }

    fun start(durationMs: Long) {
        // A replaced timer's unconsumed time is irrelevant; never expire it while starting a new one.
        lastTick = clock()
        timer.start(durationMs)
        restoreVolume()
        playback.pauseAtEndOfItem = false
        changed()
    }

    fun startEndOfEpisode() {
        // A replaced timer's unconsumed time is irrelevant; never expire it while starting a new one.
        lastTick = clock()
        timer.startEndOfEpisode()
        restoreVolume()
        playback.pauseAtEndOfItem = true
        changed()
    }

    fun extend(): Boolean {
        account()
        if (!timer.extend()) return false
        restoreVolume()
        changed()
        return true
    }

    fun cancel() {
        timer.cancel()
        stop()
        publish(null)
    }

    /** The player started or stopped playing: only playing time counts down. */
    fun playingChanged() {
        if (!timer.active) return
        account()
        changed()
    }

    fun itemChanged() {
        if (!timer.active) return
        timer.currentItemChanged(playback.hasItem)
        // Re-evaluate the fade for the new item (end of episode follows it) without consuming time.
        if (!timer.active) cancel() else step(0)
    }

    /**
     * The player paused at the end of an item, or [queueEnded]. A countdown has nothing left to
     * pause once the queue ends, so it is cancelled rather than left frozen to surprise a later session.
     */
    fun itemEnded(queueEnded: Boolean = false) {
        if (timer.itemEnded()) expire(endOfEpisode = true)
        else if (queueEnded && timer.active) cancel()
    }

    fun release() = cancel()

    /** Consumes playing time since the last tick, so a pause or change does not lose or double count it. */
    private fun account() {
        val now = clock()
        if (ticking?.isActive == true) step(now - lastTick)
        lastTick = now
    }

    private fun step(playedMs: Long) {
        when (val step = timer.advance(playedMs, playback.itemRemainingMs)) {
            null -> Unit
            is SleepTimerStep.Play -> fade(step.volume)
            SleepTimerStep.Pause -> expire(endOfEpisode = false)
        }
    }

    private fun changed() {
        if (!timer.active) return
        val run = playback.isPlaying
        if (run && ticking?.isActive != true) {
            lastTick = clock()
            // Apply the fade for the current position at once, e.g. after seeking while paused.
            step(0)
            if (!timer.active) return
            ticking = scope.launch {
                while (isActive) {
                    delay(tickMs)
                    val now = clock()
                    val played = now - lastTick
                    lastTick = now
                    step(played)
                }
            }
        } else if (!run) {
            ticking?.cancel(); ticking = null
        }
        publish(timer.status(counting = run, nowMs = clock()))
    }

    private fun expire(endOfEpisode: Boolean) {
        timer.cancel()
        playback.pause()
        stop()
        publish(null)
        onExpired(endOfEpisode)
    }

    private fun stop() {
        ticking?.cancel(); ticking = null
        restoreVolume()
        playback.pauseAtEndOfItem = false
    }

    private fun fade(volume: Float) {
        if (volume >= 1f) { restoreVolume(); return }
        val baseline = fadeBaseline ?: playback.volume.also { fadeBaseline = it }
        playback.applyVolume(baseline * volume)
    }

    private fun restoreVolume() {
        fadeBaseline?.let(playback::applyVolume)
        fadeBaseline = null
    }
}
