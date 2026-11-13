package dev.distillpod.android.sync

import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal const val SUBSCRIPTION_SYNC_WORK="subscription-sync"

internal enum class SyncWorkAction { ENQUEUE, KEEP, FOLLOW_UP, REPLACE }

/**
 * Merges a sync request with the unique work already present: nothing open starts a run; queued
 * work (including work waiting in backoff) is kept unless the user asked to sync now; a running
 * sync gets exactly one follow-up and is never cancelled.
 */
internal fun subscriptionWorkAction(states:List<WorkInfo.State>,user:Boolean):SyncWorkAction {
    val open=states.filter { !it.isFinished }
    return when {
        open.isEmpty() -> SyncWorkAction.ENQUEUE
        WorkInfo.State.RUNNING in open -> if(open.size==1) SyncWorkAction.FOLLOW_UP else SyncWorkAction.KEEP
        user -> SyncWorkAction.REPLACE
        else -> SyncWorkAction.KEEP
    }
}

/** Callers must serialize this; [SubscriptionSyncScheduler] runs it from a single consumer. */
internal suspend fun scheduleSubscriptionWork(manager:WorkManager,user:Boolean,request:()->OneTimeWorkRequest):SyncWorkAction {
    val states=withContext(Dispatchers.IO) { manager.getWorkInfosForUniqueWork(SUBSCRIPTION_SYNC_WORK).get(30,TimeUnit.SECONDS) }.map { it.state }
    val action=subscriptionWorkAction(states,user)
    val policy=when(action) {
        SyncWorkAction.KEEP -> return action
        SyncWorkAction.ENQUEUE -> ExistingWorkPolicy.KEEP
        // Appends behind the running sync; a chain that failed meanwhile is replaced instead.
        SyncWorkAction.FOLLOW_UP -> ExistingWorkPolicy.APPEND_OR_REPLACE
        SyncWorkAction.REPLACE -> ExistingWorkPolicy.REPLACE
    }
    manager.enqueueUniqueWork(SUBSCRIPTION_SYNC_WORK,policy,request()).await()
    return action
}

internal fun subscriptionSyncRequest():OneTimeWorkRequest=OneTimeWorkRequestBuilder<SubscriptionSyncWorker>()
    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()

/**
 * Thread-safe trigger coalescing. Requests from any thread land in a conflated channel; one
 * consumer waits [debounceMs] after the first request of a burst, then makes one scheduling
 * decision. A request arriving during that decision schedules once more, so none is lost.
 * A user request skips the wait.
 */
internal class SubscriptionSyncScheduler(scope:CoroutineScope,private val debounceMs:Long=300,private val schedule:suspend (user:Boolean)->Unit) {
    private val requests=Channel<Unit>(Channel.CONFLATED)
    private val user=AtomicBoolean(false)
    init {
        scope.launch {
            for(request in requests) {
                if(!user.get())delay(debounceMs)
                requests.tryReceive()
                try { schedule(user.getAndSet(false)) }
                catch(cancelled:CancellationException) { throw cancelled }
                catch(_:Exception) { /* The periodic recovery work and the next trigger retry scheduling. */ }
            }
        }
    }
    fun kick() { requests.trySend(Unit) }
    fun syncNow() { user.set(true);requests.trySend(Unit) }
}
