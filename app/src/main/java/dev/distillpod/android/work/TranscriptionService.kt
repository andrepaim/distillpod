package dev.distillpod.android.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.distillpod.android.MainActivity
import dev.distillpod.android.appGraph
import dev.distillpod.android.data.TranscriptExecution
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

/** User-started inference must not depend on a fresh JobScheduler slot per chunk. */
class TranscriptionService : Service() {
    companion object {
        private const val NOTIFICATION = 27001
        private const val PAUSE = "dev.distillpod.android.PAUSE_TRANSCRIPTION"
        private val sessionLimit = TimeUnit.HOURS.toMillis(6)
        @Volatile private var instance: TranscriptionService? = null
        val isRunning: Boolean get() = instance != null

        fun start(context: Context): Boolean {
            val active = instance
            if (active != null && !active.stopping) {
                Handler(Looper.getMainLooper()).post { if (instance === active && !active.stopping) active.kick() else start(context) }
                return true
            }
            return try {
                ContextCompat.startForegroundService(context, Intent(context, TranscriptionService::class.java))
                true
            } catch (_: IllegalStateException) { false } catch (_: SecurityException) { false }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: Job? = null
    private var generation = 0
    private var startId = 0
    private var recoveryNeeded = false
    private var unavailable = false
    @Volatile private var stopping = false
    private var wake: PowerManager.WakeLock? = null
    private val graph get() = applicationContext.appGraph

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            // Local inference consumes the media-processing allowance, not the download allowance.
            val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            // minSdk is 29. Older AndroidX Core filters out the API 35 mediaProcessing
            // bit, so use the platform overload rather than silently passing type NONE.
            startForeground(NOTIFICATION, notification("Preparing transcript", null), type)
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DistillPod:Transcription").apply {
                setReferenceCounted(false)
                acquire(sessionLimit)
            }
            scope.launch { delay(sessionLimit); stopForLimit() }
        } catch (error: RuntimeException) {
            Log.w("TranscriptionService", "Foreground session could not start; keeping queued checkpoints", error)
            unavailable = true
            recoveryNeeded = true
            stopping = true
            stopSelf() // Durable queued rows remain recoverable if Android refuses a service start.
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        this.startId = startId
        if (unavailable) return START_NOT_STICKY
        // A new start can overtake a normal stop request. Accept it while Android
        // still owns this instance instead of leaving an idle foreground service.
        stopping = false
        if (intent?.action == PAUSE) scope.launch {
            graph.ready.await()
            val episode = intent.getStringExtra("episode")
            if (episode == null) graph.transcription.pausePending("Paused. Completed chunks are saved.") else graph.transcription.pause(episode)
            kick()
        } else kick()
        return START_STICKY
    }

    private fun kick() {
        generation++
        if (controller?.isActive == true || stopping) return
        controller = scope.launch {
            recoveryNeeded = true
            graph.ready.await()
            while (isActive) {
                val observed = generation
                val row = withContext(Dispatchers.IO) { graph.transcription.nextForeground() }
                if (row == null) {
                    if (observed != generation) continue
                    stopping = true
                    if (!stopSelfResult(startId)) { stopping = false; continue }
                    return@launch
                }
                // Pause cancels only this child. The session can continue other queued episodes.
                val chunk = launch(Dispatchers.IO) {
                    graph.transcription.process(row.episodeId, row.revision, TranscriptExecution(
                        { !isActive }, {
                            val title = graph.db.library().episode(row.episodeId)?.title ?: "Preparing transcript"
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(title, row.episodeId))
                        }, false))
                }
                chunk.join()
            }
        }
    }

    private fun notification(title: String, episode: String?): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("processing", "Downloads and transcription", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getForegroundService(this, NOTIFICATION, Intent(this, TranscriptionService::class.java).setAction(PAUSE).putExtra("episode", episode), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "processing").setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(title).setContentText("Preparing transcript · continues with the screen locked")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Pause", pause).build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) { stopForLimit() }
    private fun stopForLimit() {
        unavailable = true
        scope.launch {
            try {
                graph.ready.await()
                graph.transcription.pausePending("Android's processing time limit was reached. Resume to continue from the saved chunks.")
            } finally { stopping = true; stopSelf() }
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        wake?.let { if (it.isHeld) it.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (recoveryNeeded) graph.scope.launch(Dispatchers.IO) {
            graph.ready.await()
            graph.transcription.recover()
        }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
