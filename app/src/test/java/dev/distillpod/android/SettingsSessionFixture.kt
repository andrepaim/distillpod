package dev.distillpod.android

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.distillpod.android.data.AppSettings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/**
 * Real same-file DataStore sessions with deterministic shutdown, not a fake settings repository.
 * DataStore releases its active-file reservation in a Job completion handler. On Dispatchers.IO,
 * the completed-state fast path of join can race that handler on another thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.settingsSession(
    context: Context,
    file: File,
    block: suspend (AppSettings) -> Unit,
) {
    val job = SupervisorJob()
    val scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))
    try {
        block(AppSettings(context, PreferenceDataStoreFactory.create(scope = scope) { file }))
    } finally {
        // Session work, completion handlers and the caller share one scheduler thread.
        job.cancelAndJoin()
        runCurrent()
        check(job.isCompleted && job.children.none()) { "Settings session did not shut down" }
    }
}
