package dev.distillpod.android.playback

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject

/** Explicit, app-private debug trial. No recording during ordinary owner playback. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackHealthRecorder(private val context: Context, private val player: ExoPlayer) : AnalyticsListener {
    private val directory = File(context.filesDir, "validation")
    private val enabled = File(directory, "playback-health.enabled")
    private val output = File(directory, "playback-health.jsonl")
    private var underruns = 0
    private var errors = 0
    private var buffering = 0
    private var started = 0L
    private fun active() = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
        player.currentMediaItem?.mediaId == "validation-long-playback" && enabled.isFile

    override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
        if (active()) underruns++
    }
    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) { if (active()) errors++ }
    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) { if (active() && state == Player.STATE_BUFFERING) buffering++ }

    suspend fun sample() {
        if (!active()) return
        val now = SystemClock.elapsedRealtime()
        if (started == 0L && player.isPlaying) started = now
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.getSystemService(BatteryManager::class.java)
        val value = JSONObject().put("elapsedMs", now).put("wallMs", System.currentTimeMillis())
            .put("positionMs", player.currentPosition).put("durationMs", player.duration)
            .put("playing", player.isPlaying).put("playWhenReady", player.playWhenReady)
            .put("state", player.playbackState).put("speed", player.playbackParameters.speed)
            .put("suppression", player.playbackSuppressionReason).put("underruns", underruns)
            .put("errors", errors).put("buffering", buffering).put("interactive", power.isInteractive)
            .put("locked", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
            .put("thermal", power.currentThermalStatus).put("charging", battery.isCharging)
            .put("batteryPercent", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
        withContext(Dispatchers.IO) {
            runCatching { if (output.length() < 1024 * 1024) output.appendText(value.toString() + "\n") }
        }
        // Bound unattended diagnostic playback if the host disconnects.
        if (started > 0 && now - started >= 65 * 60_000L) player.pause()
    }
}
