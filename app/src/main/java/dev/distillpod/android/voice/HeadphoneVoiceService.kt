package dev.distillpod.android.voice

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.distillpod.android.MainActivity
import dev.distillpod.android.R
import dev.distillpod.android.appGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Armed while visible, so a later headset key can use the microphone with the phone locked. */
class HeadphoneVoiceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var binding: AutoCloseable? = null
    private var wake: android.os.PowerManager.WakeLock? = null
    private val graph get() = applicationContext.appGraph
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (removed.any { headphoneInputType(it.type) }) graph.voice.cancelFrom(VoiceOrigin.HEADPHONES, false)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> { scope.launch { graph.settings.flag("headphoneVoice", false); stopSelf() }; return START_NOT_STICKY }
            CANCEL -> { graph.voice.cancelFrom(VoiceOrigin.HEADPHONES, true); if (binding == null) stopSelf(); return START_NOT_STICKY }
            DONE -> { if (graph.voice.state.value.origin == VoiceOrigin.HEADPHONES) graph.voice.finishQuestion(); if (binding == null) stopSelf(); return START_NOT_STICKY }
        }
        if (binding != null) return START_NOT_STICKY
        if (!canArm(this) || !graph.voiceApiKey.hasKey()) { stopSelf(); return START_NOT_STICKY }
        try {
            channel(this)
            ServiceCompat.startForeground(this, NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        binding = graph.headphoneVoice.attach {
            if (graph.voiceApiKey.hasKey()) {
                val route = HeadphoneAudioRoute(this)
                graph.voice.start({ PhoneVoiceInput(route.input, requireHeadset = true) }, origin = VoiceOrigin.HEADPHONES,
                    prepareAudio = { route.open() })
            } else unavailable(this)
        }
        wake = getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "DistillPod:HeadphoneVoice").apply { setReferenceCounted(false) }
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(devices, android.os.Handler(mainLooper))
        scope.launch {
            var owned = false
            graph.voice.state.map { Triple(it.phase, it.origin, it.error) }.distinctUntilChanged().collect { (phase, origin, error) ->
                if (origin == VoiceOrigin.HEADPHONES && wake?.isHeld == false) wake?.acquire(95_000)
                else if (origin != VoiceOrigin.HEADPHONES && wake?.isHeld == true) wake?.release()
                if (origin == VoiceOrigin.HEADPHONES) owned = true
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION,
                    notification(if (origin == VoiceOrigin.HEADPHONES) phase else "Ready", if (owned && phase == "Idle") error else null))
                if (phase == "Idle") owned = false
            }
        }
        scope.launch {
            combine(graph.settings.flow, graph.voiceApiKey.revisions) { prefs, _ -> prefs.headphoneVoice && graph.voiceApiKey.hasKey() }
                .distinctUntilChanged().collect { if (!it) stopSelf() }
        }
        scope.launch { graph.carConnection.collect { connected ->
            if (connected != androidx.car.app.connection.CarConnection.CONNECTION_TYPE_NOT_CONNECTED)
                graph.voice.cancelFrom(VoiceOrigin.HEADPHONES, false)
        } }
        scope.launch {
            combine(graph.playback.state, graph.voice.state) { player, voice -> player.playing || voice.active }
                .distinctUntilChanged().collectLatest { busy ->
                    if (!busy) { delay(5 * 60_000L); stopSelf() }
                }
        }
        return START_NOT_STICKY
    }
    private fun notification(phase: String = "Ready", error: String? = null): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_media_ask)
            .setContentTitle(if (phase == "Ready") "Headphone voice ready" else "Headphone voice · $phase")
            .setContentText(error ?: when (phase) {
                "Listening" -> "Ask your question. Next finishes recording."
                "Answering" -> "Microphone off. Next stops the answer and resumes."
                "Preparing" -> "Wait for the tone. Next cancels."
                else -> "Next asks about your podcast. Microphone is off."
            }).setContentIntent(open(this)).setOnlyAlertOnce(true).setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (phase == "Listening") builder.addAction(0, "Done", command(DONE))
        if (phase in listOf("Listening", "Preparing", "Answering")) builder.addAction(0, "Cancel and resume", command(CANCEL))
        builder.addAction(0, "Turn off shortcut", command(STOP))
        return builder.build()
    }
    private fun command(action: String) = PendingIntent.getService(this, action.hashCode(),
        Intent(this, HeadphoneVoiceService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    override fun onDestroy() {
        binding?.close(); binding = null
        graph.voice.cancelFrom(VoiceOrigin.HEADPHONES, false)
        getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(devices)
        scope.cancel()
        if (wake?.isHeld == true) wake?.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "headphone-voice"
        private const val NOTIFICATION = 8301
        private const val STOP = "headphone-voice.stop"
        private const val CANCEL = "headphone-voice.cancel"
        private const val DONE = "headphone-voice.done"
        fun canArm(context: Context) = context.resources.getBoolean(R.bool.voice_enabled) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        /** Invoke only from a visible Activity; a media key cannot make this service eligible. */
        fun arm(context: Context) {
            if (canArm(context)) runCatching { ContextCompat.startForegroundService(context, Intent(context, HeadphoneVoiceService::class.java)) }
        }
        fun stop(context: Context) { context.stopService(Intent(context, HeadphoneVoiceService::class.java)) }
        private fun open(context: Context) = PendingIntent.getActivity(context, 8301,
            Intent(context, MainActivity::class.java).putExtra("openPlayer", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        private fun channel(context: Context) { context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Headphone voice", NotificationManager.IMPORTANCE_LOW)) }
        fun unavailable(context: Context) {
            channel(context)
            if (androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                @Suppress("MissingPermission")
                context.getSystemService(NotificationManager::class.java).notify(8302,
                    NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_media_ask)
                        .setContentTitle("Open DistillPod to prepare voice")
                        .setContentText("Start an episode with DistillPod open. In Android Auto, open DistillPod from the car launcher.")
                        .setContentIntent(open(context)).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
            }
            android.widget.Toast.makeText(context, "Open DistillPod to prepare voice", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
