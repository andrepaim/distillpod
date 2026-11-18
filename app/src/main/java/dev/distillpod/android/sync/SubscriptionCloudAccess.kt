package dev.distillpod.android.sync

import android.content.Context
import dev.distillpod.android.R

/** Set by the test runner before Application creation; SDK tests inject demo clients explicitly. */
internal object SubscriptionCloudAccess {
    @Volatile var instrumentation: Boolean = false

    fun configured(context: Context): Boolean = !instrumentation &&
        listOf(R.string.sync_api_key, R.string.sync_web_client_id, R.string.sync_app_id, R.string.sync_project_id)
            .all { context.getString(it).isNotBlank() }
}
