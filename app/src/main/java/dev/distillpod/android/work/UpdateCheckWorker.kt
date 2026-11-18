package dev.distillpod.android.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import dev.distillpod.android.MainActivity
import dev.distillpod.android.appGraph
import dev.distillpod.android.data.AvailableUpdate
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Daily background check for a newer published APK. See docs/android/app-updates.md. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.appGraph
        if (!graph.settings.updates.first().enabled) return Result.success()
        // Offline or rate-limited: the next daily run tries again; retrying sooner would only add requests.
        val update = try { graph.updateChecker.check() } catch (_: IOException) { return Result.success() }
        if (update != null && graph.updateChecker.announce(update)) UpdateNotice.post(applicationContext, update)
        return Result.success()
    }
}

object UpdateJobs {
    private const val NAME = "update-check"

    /** Debug and test builds never check on their own; their manual check in About still works. */
    fun configure(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context)
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!enabled || debuggable) { manager.cancelUniqueWork(NAME); return }
        manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
}

object UpdateNotice {
    const val CHANNEL = "updates"
    /** Extra on [MainActivity] intents that opens Settings › About. */
    const val OPEN_ABOUT = "openAbout"
    private const val NOTIFICATION_ID = 8401

    fun post(context: Context, update: AvailableUpdate) {
        val notifications = NotificationManagerCompat.from(context)
        if (!notifications.areNotificationsEnabled()) return
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_LOW))
        val about = PendingIntent.getActivity(context, NOTIFICATION_ID, Intent(context, MainActivity::class.java).putExtra(OPEN_ABOUT, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val download = PendingIntent.getActivity(context, 2, Intent(Intent.ACTION_VIEW, Uri.parse(update.apkUrl)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("DistillPod ${update.versionName} is available")
            .setContentText(update.summary.ifBlank { "Tap to see what's new." })
            .setStyle(NotificationCompat.BigTextStyle().bigText(update.summary.ifBlank { "Tap to see what's new." }))
            .setContentIntent(about).setAutoCancel(true)
            .addAction(0, "Download APK", download).build()
        // areNotificationsEnabled covers the Android 13 notification permission; without it About still shows the update.
        @Suppress("MissingPermission")
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }
}
