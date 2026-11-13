package dev.distillpod.android.widget

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import dev.distillpod.android.PlaybackService
import dev.distillpod.android.auto.AutoLibraryCallback
import dev.distillpod.android.playback.PlaybackControls

/** What a widget button does with the live player, which may differ from the launcher's last drawing. */
internal sealed interface WidgetAction {
    data object TogglePlayback : WidgetAction
    data object SaveMoment : WidgetAction
    data class Explain(val message: String) : WidgetAction
}

internal fun widgetAction(action: String?, hasItem: Boolean, positionMs: Long): WidgetAction? = when (action) {
    NowPlayingWidget.ACTION_TOGGLE -> WidgetAction.TogglePlayback
    NowPlayingWidget.ACTION_DISTILL -> when {
        !hasItem -> WidgetAction.Explain("Play an episode first")
        !captureAllowed(true, positionMs) -> WidgetAction.Explain("Distill becomes available once some of the episode has played")
        else -> WidgetAction.SaveMoment
    }
    else -> null
}

internal fun captureResultMessage(resultCode: Int): String = when (resultCode) {
    SessionResult.RESULT_SUCCESS -> "Moment saved"
    SessionError.INFO_CANCELLED -> "Already saving a moment"
    SessionError.ERROR_INVALID_STATE -> "Play an episode first"
    else -> "Couldn't save moment. Try again."
}

/** The session command the media notification, Android Auto and headphones use, attributed to the phone. */
internal val widgetCaptureArgs get() = Bundle().apply { putString(AutoLibraryCallback.CAPTURE_SOURCE, "phone") }

/**
 * Not exported: only this app's widget PendingIntents reach it. Each tap connects to the existing
 * media session, acts on its live state and disconnects; the session's own listener redraws the widget.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NowPlayingWidgetActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requested = intent.action?.takeIf { it == NowPlayingWidget.ACTION_TOGGLE || it == NowPlayingWidget.ACTION_DISTILL } ?: return
        val app = context.applicationContext
        val pending = goAsync()
        val main = ContextCompat.getMainExecutor(app)
        val handler = Handler(Looper.getMainLooper())
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        var done = false
        fun finish(message: String? = null) {
            if (done) return
            done = true
            handler.removeCallbacksAndMessages(null)
            message?.let { Toast.makeText(app, it, Toast.LENGTH_SHORT).show() }
            MediaController.releaseFuture(future)
            pending.finish()
        }
        // Stay well inside the broadcast deadline; an accepted capture still completes in the session.
        handler.postDelayed({ finish() }, TIMEOUT_MS)
        future.addListener({
            val controller = runCatching { future.get() }.getOrNull() ?: return@addListener finish("DistillPod playback is unavailable")
            when (val action = widgetAction(requested, controller.currentMediaItem != null, controller.currentPosition)) {
                WidgetAction.TogglePlayback -> {
                    val starting = Util.shouldShowPlayButton(controller)
                    Util.handlePlayPauseButtonAction(controller)
                    // A started session goes foreground once its player is playing or buffering with play intent
                    // (Media3 can first resume the saved queue). Disconnecting earlier could stop an unbound service.
                    if (!starting) return@addListener finish()
                    val settle: (Player) -> Unit = { player ->
                        if (playbackOngoing(player)) handler.postDelayed({ finish() }, FOREGROUND_GRACE_MS)
                        else if (!player.playWhenReady) finish()
                    }
                    controller.addListener(object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) = settle(player)
                    })
                    settle(controller)
                }
                WidgetAction.SaveMoment -> {
                    val result = controller.sendCustomCommand(PlaybackControls.distill, widgetCaptureArgs)
                    result.addListener({
                        finish(captureResultMessage(runCatching { result.get().resultCode }.getOrDefault(SessionError.ERROR_UNKNOWN)))
                    }, main)
                }
                is WidgetAction.Explain -> finish(action.message)
                null -> finish()
            }
        }, main)
    }

    private companion object {
        const val TIMEOUT_MS = 8_000L
        const val FOREGROUND_GRACE_MS = 500L
        fun playbackOngoing(player: Player) = player.playWhenReady &&
            player.playbackState in setOf(Player.STATE_BUFFERING, Player.STATE_READY)
    }
}

