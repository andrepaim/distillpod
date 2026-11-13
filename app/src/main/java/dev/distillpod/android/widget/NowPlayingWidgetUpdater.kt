package dev.distillpod.android.widget

import android.content.Context
import androidx.media3.common.Player

/**
 * Redraws the widget from the session player on playback events only: no polling, no position
 * ticks, and identical states are not sent again. Lives exactly as long as PlaybackService.
 */
internal class NowPlayingWidgetUpdater(private val player: Player,
    private val publish: (NowPlayingWidgetState) -> Unit) : Player.Listener {
    private var last: NowPlayingWidgetState? = null

    fun start() { player.addListener(this); render() }

    /** The session is going away: whatever the launcher still shows must not look playable. */
    fun stop() {
        player.removeListener(this)
        NowPlayingWidget.live = null
        last = null
        publish(NowPlayingWidgetState.IDLE)
    }

    override fun onEvents(player: Player, events: Player.Events) { if (events.containsAny(*EVENTS)) render() }

    private fun render() {
        val state = nowPlayingWidgetState(player)
        NowPlayingWidget.live = state
        if (state == last) return
        last = state
        publish(state)
    }

    companion object {
        private val EVENTS = intArrayOf(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
            Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED,
            Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED, Player.EVENT_POSITION_DISCONTINUITY)

        fun attach(context: Context, player: Player): NowPlayingWidgetUpdater {
            val app = context.applicationContext
            return NowPlayingWidgetUpdater(player) { NowPlayingWidget.publish(app, it) }.also { it.start() }
        }
    }
}
