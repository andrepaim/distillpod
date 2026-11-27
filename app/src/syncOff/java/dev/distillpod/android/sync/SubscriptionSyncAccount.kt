package dev.distillpod.android.sync

import androidx.work.WorkManager
import dev.distillpod.android.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The inert stand-in of a build without library sync (distillpodSyncEnabled=false, the Play preset). It has
 * no sign-in and no cloud: it is never configured, never connects and schedules nothing. Local library code
 * keeps calling [kick], which does nothing here. See docs/android/subscription-sync.md.
 */
class SubscriptionSyncAccount(private val graph: AppGraph) {
    /** The same shape as the sync build's state, permanently unconfigured. */
    data class State(val configured: Boolean = false, val connected: Boolean = false, val uid: String = "", val busy: Boolean = false,
        val email: String = "", val message: String = "")
    val store = SubscriptionSyncStore(graph.db)
    val state: StateFlow<State> = MutableStateFlow(State()).asStateFlow()
    val pending = graph.db.sync().observeCount()
    val fullScope = graph.db.sync().observeView("__extended_scope")
    val contentConfigured: Boolean get() = false
    init {
        // Sync work an earlier GitHub build may have left; WorkManager needs no worker class to cancel it.
        WorkManager.getInstance(graph.context).apply {
            cancelUniqueWork(SUBSCRIPTION_SYNC_RECOVERY_WORK)
            cancelUniqueWork(SUBSCRIPTION_SYNC_WORK)
        }
    }
    fun kick() {}
    fun syncNow() {}
    @Suppress("UNUSED_PARAMETER")
    fun signedIn(uid: String): Boolean = false
    /** Nothing to send: a completed run, so no caller retries. */
    suspend fun syncOnce(): Boolean = true
}
