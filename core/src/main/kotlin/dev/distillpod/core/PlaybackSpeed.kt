package dev.distillpod.core

import kotlin.math.roundToInt

/** Supported playback speeds: 0.5–3.0× in 0.1× steps, plus the quick presets. */
object PlaybackSpeeds {
    const val MIN = 0.5f
    const val MAX = 3f
    val presets = listOf(1f, 1.25f, 1.5f, 2f)
    private const val MIN_HUNDREDTHS = 50
    private const val MAX_HUNDREDTHS = 300

    /** Speeds are compared in hundredths so binary float noise cannot skip or repeat a step. */
    private fun hundredths(speed: Float) = (speed * 100).roundToInt()
    private fun speed(hundredths: Int) = hundredths / 100f

    /** The value rounded to hundredths, or null when it is not a supported speed. */
    fun normalize(speed: Float): Float? =
        if (!speed.isFinite()) null else hundredths(speed).takeIf { it in MIN_HUNDREDTHS..MAX_HUNDREDTHS }?.let(::speed)

    /** The next tenth above [current], clamped to [MAX]. */
    fun faster(current: Float): Float = speed(((hundredths(current) / 10 + 1) * 10).coerceIn(MIN_HUNDREDTHS, MAX_HUNDREDTHS))

    /** The previous tenth below [current], clamped to [MIN]. */
    fun slower(current: Float): Float {
        val value = hundredths(current)
        val tenths = if (value % 10 == 0) value / 10 - 1 else Math.floorDiv(value, 10)
        return speed((tenths * 10).coerceIn(MIN_HUNDREDTHS, MAX_HUNDREDTHS))
    }
    fun canSpeedUp(current: Float) = hundredths(current) < MAX_HUNDREDTHS
    fun canSlowDown(current: Float) = hundredths(current) > MIN_HUNDREDTHS

    /** "1×", "1.25×", "1.3×": at most two decimals, always with a period. */
    fun label(speed: Float): String {
        val value = hundredths(speed)
        val fraction = (value % 100).toString().padStart(2, '0').trimEnd('0')
        return if (fraction.isEmpty()) "${value / 100}×" else "${value / 100}.$fraction×"
    }
}

/**
 * Device-local speed preferences: the global speed and the shows that keep their own.
 * [global] is null until a speed has been chosen with these preferences; the current speed is then kept.
 */
data class SpeedPreferences(val global: Float? = null, val shows: Map<String, Float> = emptyMap()) {
    /** The speed for an item of [show], or null to keep the current speed. */
    fun target(show: String?): Float? = show?.let(shows::get) ?: global
    fun owns(show: String?) = show != null && show in shows

    /** Records [speed] chosen while [show] plays: for that show when it keeps its own, otherwise globally. */
    fun chosen(show: String?, speed: Float): SpeedPreferences {
        val value = PlaybackSpeeds.normalize(speed) ?: return this
        return if (show != null && owns(show)) copy(shows = shows + (show to value)) else copy(global = value)
    }

    /** [show] keeps [speed] from now on. An unknown global speed starts from it too. */
    fun keep(show: String, speed: Float): SpeedPreferences {
        val value = PlaybackSpeeds.normalize(speed) ?: return this
        return copy(global = global ?: value, shows = shows + (show to value))
    }

    fun forget(show: String): SpeedPreferences = copy(shows = shows - show)
}
