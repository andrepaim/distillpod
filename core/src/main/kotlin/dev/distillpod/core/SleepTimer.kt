package dev.distillpod.core

/** Sleep timer durations offered on Now Playing, in minutes. */
val SLEEP_TIMER_MINUTES = listOf(5, 10, 15, 30, 45, 60)
/** The volume fades out over this much playing time before the timer pauses. */
const val SLEEP_TIMER_FADE_MS = 15_000L
/** One "extend" adds this much time to a running countdown. */
const val SLEEP_TIMER_EXTENSION_MS = 5 * 60_000L

/** What the playback owner applies after advancing the timer. */
sealed interface SleepTimerStep {
    /** Keep playing at [volume]: 1 outside the fade window. */
    data class Play(val volume: Float) : SleepTimerStep
    /** Pause now. The timer has been cleared. */
    data object Pause : SleepTimerStep
}

/**
 * Presentation state anchored to a monotonic clock, so a viewer can count down locally and the
 * owner only republishes on changes (start, extend, cancel, play/pause), not every second.
 */
data class SleepTimerStatus(val endOfEpisode: Boolean, val remainingMs: Long, val counting: Boolean, val anchorMs: Long) {
    fun remainingAt(nowMs: Long): Long =
        if (counting) (remainingMs - (nowMs - anchorMs).coerceAtLeast(0)).coerceAtLeast(0) else remainingMs
}

/** Perceptually smoother than a linear ramp: full volume at [fadeMs] remaining, silent at zero. */
fun sleepFadeVolume(remainingMs: Long, fadeMs: Long): Float {
    if (remainingMs >= fadeMs) return 1f
    if (remainingMs <= 0) return 0f
    val fraction = remainingMs.toFloat() / fadeMs
    return fraction * fraction
}

/**
 * Sleep timer state owned by the playback service. A countdown consumes playing time only, so a
 * manual pause freezes it. End of episode applies to whichever item is current and expires when
 * that item ends. The state is in memory: it does not survive the service or process.
 */
class SleepTimer(private val fadeMs: Long = SLEEP_TIMER_FADE_MS) {
    var endOfEpisode = false
        private set
    /** Remaining playing time of a countdown; zero for end of episode or when inactive. */
    var remainingMs = 0L
        private set
    val active get() = endOfEpisode || remainingMs > 0

    fun start(durationMs: Long) {
        require(durationMs > 0) { "Sleep timer duration must be positive" }
        endOfEpisode = false
        remainingMs = durationMs
    }

    fun startEndOfEpisode() {
        endOfEpisode = true
        remainingMs = 0
    }

    /** Adds time to a running countdown; end of episode has no duration to extend. */
    fun extend(byMs: Long = SLEEP_TIMER_EXTENSION_MS): Boolean {
        if (endOfEpisode || remainingMs <= 0 || byMs <= 0) return false
        remainingMs += byMs
        return true
    }

    fun cancel() {
        endOfEpisode = false
        remainingMs = 0
    }

    /**
     * Consumes [playedMs] of playing time. [episodeRemainingMs] is the current item's remaining
     * playing time at the current speed, when known. Null when the timer is inactive.
     */
    fun advance(playedMs: Long, episodeRemainingMs: Long?): SleepTimerStep? {
        if (endOfEpisode) return SleepTimerStep.Play(episodeRemainingMs?.let { sleepFadeVolume(it, fadeMs) } ?: 1f)
        if (remainingMs <= 0) return null
        remainingMs = (remainingMs - playedMs.coerceAtLeast(0)).coerceAtLeast(0)
        if (remainingMs == 0L) return SleepTimerStep.Pause
        return SleepTimerStep.Play(sleepFadeVolume(remainingMs, fadeMs))
    }

    /** The current item played to its end. True when this expires an end-of-episode timer. */
    fun itemEnded(): Boolean {
        if (!endOfEpisode) return false
        cancel()
        return true
    }

    /** A countdown continues across items and end of episode follows the new item; no item leaves nothing to pause. */
    fun currentItemChanged(present: Boolean) {
        if (!present) cancel()
    }

    fun status(counting: Boolean, nowMs: Long): SleepTimerStatus? =
        if (active) SleepTimerStatus(endOfEpisode, remainingMs, counting && !endOfEpisode, nowMs) else null
}
