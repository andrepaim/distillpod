package dev.distillpod.android.auto

import androidx.media3.common.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Decides which subscribed browse lists the host must reload. A children-changed notification makes Android Auto
 * fetch and rebuild the whole list, including while the driver is scrolling it (#277), so a list is announced only
 * when a row it shows actually differs from what the host last received or was told to fetch.
 */
internal class AutoChildrenChanges {
    /** The parts of a row the host renders; media IDs are stable, so a changed value means a visibly different row. */
    private data class Row(val id: String, val title: String?, val artist: String?, val subtitle: String?,
        val artwork: String?, val browsable: Boolean?, val playable: Boolean?)
    private fun rows(children: List<MediaItem>) = children.map {
        val m = it.mediaMetadata
        Row(it.mediaId, m.title?.toString(), m.artist?.toString(), m.subtitle?.toString(), m.artworkUri?.toString(), m.isBrowsable, m.isPlayable)
    }
    private val known = HashMap<String, List<Row>>()

    /** The host received [children] as the complete list of [parent]. */
    @Synchronized fun served(parent: String, children: List<MediaItem>) { known[parent] = rows(children) }

    /** Whether the host must reload [parent]; if so, [children] becomes the state it is being told about. */
    @Synchronized fun changed(parent: String, children: List<MediaItem>): Boolean {
        val current = rows(children)
        if (known[parent] == current) return false
        known[parent] = current
        return true
    }

    @Synchronized fun forget(parent: String) { known.remove(parent) }
}

/**
 * Runs [action] at most once per [windowMs], however many changes are [signal]led. Database writes during a feed
 * refresh and cover downloads each signal separately; without coalescing every one re-sent the browse lists. A
 * signal that arrives while [action] runs schedules exactly one more run, so the final state is always published.
 */
internal class LibraryChangeThrottle(scope: CoroutineScope, private val windowMs: Long, private val action: suspend () -> Unit) {
    private val signals = Channel<Unit>(Channel.CONFLATED)
    init {
        scope.launch {
            for (signal in signals) {
                delay(windowMs)
                signals.tryReceive()
                // One failed refresh must not stop later lists from updating.
                try { action() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) {}
            }
        }
    }
    fun signal() { signals.trySend(Unit) }
}
