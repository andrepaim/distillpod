package dev.distillpod.android.features

import androidx.room.withTransaction
import dev.distillpod.android.AppGraph
import dev.distillpod.android.StartupRecovery
import dev.distillpod.core.FirstRunEvidence
import dev.distillpod.core.FirstRunSetup
import dev.distillpod.core.firstRunSetup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Process-local setting set by the isolated runner before production startup. */
internal object FirstRunTestAccess {
    @Volatile var bypass = false
}

@Suppress("DEPRECATION") // Keep the minSdk-compatible PackageManager overload.
internal suspend fun AppGraph.loadFirstRunSetup(): FirstRunSetup {
    if (FirstRunTestAccess.bypass) return FirstRunSetup.ExistingInstall
    return try {
        settings.firstRunSetup.first()?.let { return it }
        val installed = withContext(Dispatchers.IO) { context.packageManager.getPackageInfo(context.packageName, 0) }
        val packageEvidence = FirstRunEvidence(installed.firstInstallTime, installed.lastUpdateTime)
        val evidence = if (packageEvidence.neverUpdated) {
            // Startup can import a legacy library. Read actual rows only after it has had a chance to do so.
            ready.await()
            db.withTransaction {
                val library = db.library()
                packageEvidence.copy(
                    hasFollowedShows = library.hasFollowedShows(),
                    hasEpisodes = library.hasEpisodes(),
                    hasSavedMoments = library.hasSavedMoments(),
                    hasSyncBinding = db.sync().binding() != null,
                )
            }.copy(chatGptConnected = account.state.value.connected, hasBackupFile = managedBackups.connected.first())
        } else packageEvidence
        settings.initializeFirstRunSetup(firstRunSetup(evidence))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // Unavailable evidence cannot establish a fresh install. Try again on a later launch.
        StartupRecovery.report(diagnostics, "first_run", error)
        FirstRunSetup.ExistingInstall
    }
}
