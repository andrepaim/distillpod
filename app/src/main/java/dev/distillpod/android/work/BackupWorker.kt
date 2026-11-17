package dev.distillpod.android.work

import android.content.Context
import androidx.work.*
import dev.distillpod.android.appGraph
import dev.distillpod.android.data.ManagedBackups
import java.util.concurrent.TimeUnit

/**
 * Daily automatic backup to the connected backup file. The outcome is stored by ManagedBackups and shown on
 * the Sync page; this worker only decides whether a retry can help. See docs/android/sync-only-settings.md.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (val outcome = applicationContext.appGraph.managedBackups.automatic()) {
        ManagedBackups.Automatic.Saved, ManagedBackups.Automatic.Unchanged, ManagedBackups.Automatic.NotConnected -> Result.success()
        // A manual backup or file picker is in progress: try again shortly rather than tomorrow.
        ManagedBackups.Automatic.Busy -> Result.retry()
        is ManagedBackups.Automatic.Failed -> BackupJobs.afterFailure(outcome.needsUser, runAttemptCount)
    }
}

object BackupJobs {
    private const val NAME = "managed-backup"
    private const val RETRIES = 2

    /** Lost access or an unsupported file waits for the user; other failures retry twice, then wait a day. */
    fun afterFailure(needsUser: Boolean, attempts: Int): ListenableWorker.Result =
        if (!needsUser && attempts < RETRIES) ListenableWorker.Result.retry() else ListenableWorker.Result.success()

    fun request(): PeriodicWorkRequest = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
        .build()

    /** Scheduled whenever a backup file is connected, in every build type; cancelled when it is forgotten. */
    fun configure(context: Context, connected: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (connected) manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request())
        else manager.cancelUniqueWork(NAME)
    }
}
