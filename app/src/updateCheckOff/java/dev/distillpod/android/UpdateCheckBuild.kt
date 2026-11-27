package dev.distillpod.android

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.work.WorkManager
import dev.distillpod.android.data.UPDATE_CHECK_WORK
import dev.distillpod.android.data.UpdateState

/**
 * This build has no update check (distillpodUpdateCheckEnabled=false, the Play preset): a store updates it.
 * It never requests GitHub, schedules nothing and shows no setting or notice. See docs/android/app-updates.md.
 */
internal object UpdateCheckBuild {
    const val ENABLED = false

    /** Cancels a daily check an earlier GitHub build may have left; WorkManager needs no worker class for that. */
    @Suppress("UNUSED_PARAMETER")
    fun configure(context: Context, enabled: Boolean) { WorkManager.getInstance(context).cancelUniqueWork(UPDATE_CHECK_WORK) }

    @Suppress("UNUSED_PARAMETER")
    suspend fun check(graph: AppGraph): String? = null

    @Suppress("UNUSED_PARAMETER")
    @Composable fun AboutSection(state: UpdateState, checking: Boolean, onToggle: (Boolean) -> Unit, onCheck: () -> Unit) {}
}
