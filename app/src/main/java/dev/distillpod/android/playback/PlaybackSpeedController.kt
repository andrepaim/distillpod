package dev.distillpod.android.playback

import dev.distillpod.core.PlaybackSpeeds
import dev.distillpod.core.SpeedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The player speed seen by [PlaybackSpeedController]. */
internal interface SpeedPlayer { var speed: Float }

/**
 * Applies the show's own speed, or the global speed, when the current item changes, and records the
 * listener's speed choices for the show that keeps its own speed, otherwise globally (#183).
 * Runs on the player's thread. [showOf] resolves an episode's show; [save] atomically updates the
 * stored preferences, whose changes come back through [preferencesChanged].
 */
internal class PlaybackSpeedController(
    private val scope: CoroutineScope,
    private val player: SpeedPlayer,
    private val showOf: suspend (episodeId: String) -> String?,
    private val save: suspend ((SpeedPreferences) -> SpeedPreferences) -> Unit,
    private val ignoreChanges: () -> Boolean = { false },
    private val onFailure: (Throwable) -> Unit = {},
) {
    private var preferences: SpeedPreferences? = null
    private var item: String? = null
    private var show: String? = null
    private var resolved = true
    private var applyDue = false
    /** A speed chosen before the current item's show or the preferences were known. */
    private var pending: Float? = null
    private var lookup: Job? = null
    /** The speed this controller last applied; its report back is not a choice. */
    private var applied: Float? = null

    fun preferencesChanged(value: SpeedPreferences) {
        val previous = preferences
        preferences = value
        // Forgetting the current show's own speed returns to the global speed.
        if (resolved && previous != null && previous.owns(show) && !value.owns(show)) applyDue = true
        settle()
    }

    fun itemChanged(id: String?) {
        if (id == item) return
        lookup?.cancel()
        item = id; show = null; pending = null
        if (id == null) { resolved = true; applyDue = false; return }
        resolved = false; applyDue = true
        lookup = scope.launch {
            val found = try { showOf(id) } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { onFailure(error); null }
            if (item != id) return@launch
            show = found; resolved = true
            settle()
        }
    }

    fun speedChanged(speed: Float) {
        if (ignoreChanges()) return
        val value = PlaybackSpeeds.normalize(speed) ?: return
        val own = value == applied
        applied = null
        if (own) return
        val prefs = preferences
        if (!resolved || prefs == null) {
            // The listener chose this speed for the new item: keep it rather than applying a stored one.
            pending = value; applyDue = false; return
        }
        record(prefs, value)
    }

    private fun settle() {
        val prefs = preferences ?: return
        if (!resolved) return
        pending?.let { value -> pending = null; applyDue = false; if (prefs.target(show) != value) record(prefs, value); return }
        if (!applyDue) return
        applyDue = false
        val target = prefs.target(show) ?: return
        if (PlaybackSpeeds.normalize(player.speed) != target) { applied = target; player.speed = target }
    }

    private fun record(prefs: SpeedPreferences, value: Float) {
        val owner = show
        preferences = prefs.chosen(owner, value)
        scope.launch {
            try { save { it.chosen(owner, value) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { onFailure(error) }
        }
    }
}
