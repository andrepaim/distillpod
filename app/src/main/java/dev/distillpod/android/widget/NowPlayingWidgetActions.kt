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

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
        var timeoutMessage: () -> String? = { null }
        fun finish(message: String? = null) {
            if (done) return
            done = true
            handler.removeCallbacksAndMessages(null)
            message?.let { Toast.makeText(app, it, Toast.LENGTH_SHORT).show() }
            MediaController.releaseFuture(future)
            pending.finish()
        }
        // Stay well inside the broadcast deadline; an accepted capture still completes in the session.
        handler.postDelayed({ finish(timeoutMessage()) }, TIMEOUT_MS)
        future.addListener({
            val controller = runCatching { future.get() }.getOrNull() ?: return@addListener finish("DistillPod playback is unavailable")
            var acted = false
            fun act() {
                if (acted || done) return
                acted = true
                when (val action = widgetAction(requested, controller.currentMediaItem != null, controller.currentPosition)) {
                    WidgetAction.TogglePlayback -> togglePlayback(controller, handler, { timeoutMessage = it }, ::finish)
                    WidgetAction.SaveMoment -> {
                        val result = controller.sendCustomCommand(PlaybackControls.distill, widgetCaptureArgs)
                        result.addListener({
                            finish(captureResultMessage(runCatching { result.get().resultCode }.getOrDefault(SessionError.ERROR_UNKNOWN)))
                        }, main)
                    }
                    is WidgetAction.Explain -> finish(action.message)
                    null -> finish()
                }
            }
            // After process death the binding creates the service, which restores the saved queue
            // asynchronously. Give it a moment so the tap acts on that episode, not on an empty player.
            if (controller.currentMediaItem != null) act()
            else {
                controller.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) { if (player.currentMediaItem != null) act() }
                })
                handler.postDelayed({ act() }, RESTORE_WAIT_MS)
            }
        }, main)
    }

    /**
     * Pausing needs nothing more. Starting must keep this binding until the session plays: a service
     * created by this tap is only bound, and goes foreground (started) once playback is under way.
     * Media3 may first resume the saved queue, so intermediate paused states are not final.
     */
    private fun togglePlayback(controller: MediaController, handler: Handler, onTimeout: (() -> String?) -> Unit,
        finish: (String?) -> Unit) {
        val starting = Util.shouldShowPlayButton(controller)
        Util.handlePlayPauseButtonAction(controller)
        if (!starting) return finish(null)
        onTimeout { if (controller.isPlaying || playbackOngoing(controller)) null else "Couldn't start playback" }
        val settle: (Player) -> Unit = { player ->
            if (player.isPlaying) handler.postDelayed({ finish(null) }, FOREGROUND_GRACE_MS)
            else if (player.playerError != null) finish("Couldn't start playback")
        }
        controller.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = settle(player)
        })
        settle(controller)
    }

    private companion object {
        const val TIMEOUT_MS = 9_000L
        const val RESTORE_WAIT_MS = 2_500L
        const val FOREGROUND_GRACE_MS = 1_500L
        fun playbackOngoing(player: Player) = player.playWhenReady &&
            player.playbackState in setOf(Player.STATE_BUFFERING, Player.STATE_READY)
    }
}

