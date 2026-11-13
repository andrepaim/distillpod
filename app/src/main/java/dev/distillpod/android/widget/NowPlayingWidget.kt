package dev.distillpod.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import dev.distillpod.android.MainActivity
import dev.distillpod.android.R

/**
 * What the home-screen widget shows (#189). Only rendered values: the media session owns playback.
 * Titles are the same ones the media notification shows; nothing else leaves the app.
 */
internal data class NowPlayingWidgetState(val title: String? = null, val show: String? = null,
    val playing: Boolean = false, val captureAvailable: Boolean = false) {
    val idle get() = title == null
    companion object { val IDLE = NowPlayingWidgetState() }
}

/** "Distill last 60 seconds" needs some audio before the position; at 0:00 there is none (as in Now Playing, #146). */
internal const val MIN_CAPTURE_POSITION_MS = 1_000L
internal fun captureAllowed(hasItem: Boolean, positionMs: Long) = hasItem && positionMs >= MIN_CAPTURE_POSITION_MS

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun nowPlayingWidgetState(player: Player): NowPlayingWidgetState {
    val item = player.currentMediaItem ?: return NowPlayingWidgetState.IDLE
    val metadata = player.mediaMetadata
    val title = (metadata.title ?: item.mediaMetadata.title)?.toString()?.takeIf { it.isNotBlank() } ?: "DistillPod"
    val show = (metadata.artist ?: item.mediaMetadata.artist)?.toString()?.takeIf { it.isNotBlank() }
    val playing = !Util.shouldShowPlayButton(player)
    // The widget is not redrawn as the position advances. Playing audio passes 0:01 almost at once,
    // and the action receiver checks the live position before asking the session to save.
    return NowPlayingWidgetState(title, show, playing, playing || captureAllowed(true, player.currentPosition))
}

internal object NowPlayingWidget {
    const val ACTION_TOGGLE = "dev.distillpod.android.widget.TOGGLE"
    const val ACTION_DISTILL = "dev.distillpod.android.widget.DISTILL"
    private const val REQUEST_OPEN_PLAYER = 40
    private const val REQUEST_OPEN_APP = 41
    private const val REQUEST_TOGGLE = 42
    private const val REQUEST_DISTILL = 43

    /** The state last rendered by a running PlaybackService in this process; null without one. */
    @Volatile var live: NowPlayingWidgetState? = null

    fun actionIntent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(context,
        if (action == ACTION_DISTILL) REQUEST_DISTILL else REQUEST_TOGGLE,
        Intent(action).setClass(context, NowPlayingWidgetActions::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun openIntent(context: Context, player: Boolean): PendingIntent = PendingIntent.getActivity(context,
        if (player) REQUEST_OPEN_PLAYER else REQUEST_OPEN_APP,
        Intent(context, MainActivity::class.java).apply { if (player) putExtra("openPlayer", true) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun views(context: Context, state: NowPlayingWidgetState): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_now_playing).apply {
            setTextViewText(R.id.widget_title, state.title ?: context.getString(R.string.widget_idle_title))
            setTextViewText(R.id.widget_show, if (state.idle) context.getString(R.string.widget_idle_detail) else state.show ?: "DistillPod")
            setOnClickPendingIntent(R.id.widget_root, openIntent(context, !state.idle))
            setImageViewResource(R.id.widget_toggle, if (state.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
            setContentDescription(R.id.widget_toggle, if (state.playing) "Pause" else context.getString(R.string.widget_play))
            setOnClickPendingIntent(R.id.widget_toggle, actionIntent(context, ACTION_TOGGLE))
            setBoolean(R.id.widget_distill, "setEnabled", state.captureAvailable)
            setInt(R.id.widget_distill, "setImageAlpha", if (state.captureAvailable) 255 else 97)
            setContentDescription(R.id.widget_distill, context.getString(R.string.widget_distill))
            setOnClickPendingIntent(R.id.widget_distill, actionIntent(context, ACTION_DISTILL))
        }

    fun publish(context: Context, state: NowPlayingWidgetState) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, NowPlayingWidgetProvider::class.java)) }.getOrNull()
        if (ids == null || ids.isEmpty()) return
        runCatching { manager.updateAppWidget(ids, views(context, state)) }
    }
}
