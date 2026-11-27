package dev.distillpod.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.distillpod.android.data.ReleaseFeed
import dev.distillpod.android.data.UpdateChecker
import dev.distillpod.android.data.UpdateState
import dev.distillpod.android.features.AppUpdatesSection
import dev.distillpod.android.work.UpdateJobs
import java.io.IOException

/** This build checks GitHub for updates (distillpodUpdateCheckEnabled, the default). See docs/android/app-updates.md. */
internal object UpdateCheckBuild {
    const val ENABLED = true

    /** Schedules or cancels the daily check, at startup and when the About switch changes. */
    fun configure(context: Context, enabled: Boolean) = UpdateJobs.configure(context, enabled)

    /** A manual check from About; returns the message to show, or null when a newer version is offered. */
    suspend fun check(graph: AppGraph): String? {
        val checker = graph.updateChecker
        val update = try { checker.check() } catch (_: IOException) {
            return "Couldn't reach GitHub to check for updates. Try again later."
        }
        if (update == null) return "You have the latest version."
        // Seen here, so the daily check does not announce the same version again.
        checker.announce(update)
        return null
    }

    @Composable fun AboutSection(state: UpdateState, checking: Boolean, onToggle: (Boolean) -> Unit, onCheck: () -> Unit) {
        val context = LocalContext.current
        AppUpdatesSection(state, checking, System.currentTimeMillis(), onToggle, onCheck) { url ->
            if (ReleaseFeed.isProjectRelease(url)) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }
}

/** Stateless: the stored result and announcements live in the settings. */
internal val AppGraph.updateChecker get() = UpdateChecker(ReleaseFeed(http)::newest, settings, installedVersionCode)
