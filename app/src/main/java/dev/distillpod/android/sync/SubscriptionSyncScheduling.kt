package dev.distillpod.android.sync

import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal const val SUBSCRIPTION_SYNC_WORK="subscription-sync"

internal enum class SyncWorkAction { ENQUEUE, KEEP, FOLLOW_UP, REPLACE, SKIP }
internal data class SyncWorkSnapshot(val state:WorkInfo.State,val runAttemptCount:Int=0,val nextScheduleTimeMillis:Long=Long.MAX_VALUE)

/** Backoff ending sooner than this is treated as about to run, so a user request does not race its start. */
private const val BACKOFF_REPLACE_MARGIN_MS=5_000L

/**
 * Merges a sync request with the unique work already present: nothing open starts a run; queued
 * work is kept; a running sync gets exactly one follow-up and is never cancelled. A user request
 * replaces only a run that is waiting in retry backoff. WorkManager has no conditional replace, so
 * the margin narrows, but cannot remove, the window in which that run starts meanwhile.
 */
internal fun subscriptionWorkAction(work:List<SyncWorkSnapshot>,user:Boolean,now:Long):SyncWorkAction {
    val open=work.filter { !it.state.isFinished }
    return when {
        open.isEmpty() -> SyncWorkAction.ENQUEUE
        open.any { it.state==WorkInfo.State.RUNNING } -> if(open.size==1) SyncWorkAction.FOLLOW_UP else SyncWorkAction.KEEP
        user && open.any { it.state==WorkInfo.State.ENQUEUED && it.runAttemptCount>0 && it.nextScheduleTimeMillis>now+BACKOFF_REPLACE_MARGIN_MS } -> SyncWorkAction.REPLACE
        else -> SyncWorkAction.KEEP
    }
}

/** Callers must serialize this; [SubscriptionSyncScheduler] runs it from a single consumer. */
internal suspend fun scheduleSubscriptionWork(manager:WorkManager,user:Boolean,active:Boolean=true,request:()->OneTimeWorkRequest):SyncWorkAction {
    if(!active)return SyncWorkAction.SKIP
    val work=withContext(Dispatchers.IO) { manager.getWorkInfosForUniqueWork(SUBSCRIPTION_SYNC_WORK).get(30,TimeUnit.SECONDS) }
        .map { SyncWorkSnapshot(it.state,it.runAttemptCount,it.nextScheduleTimeMillis) }
    return enqueueSubscriptionWork(manager,subscriptionWorkAction(work,user,System.currentTimeMillis()),request)
}

internal suspend fun enqueueSubscriptionWork(manager:WorkManager,action:SyncWorkAction,request:()->OneTimeWorkRequest):SyncWorkAction {
    val policy=when(action) {
        SyncWorkAction.KEEP,SyncWorkAction.SKIP -> return action
        SyncWorkAction.ENQUEUE -> ExistingWorkPolicy.KEEP
        // Appends behind the running sync; if it finished meanwhile, starts or replaces the chain.
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
 * Thread-safe trigger coalescing. Requests from any thread land in a conflated channel read by one
 * consumer, which waits until no request arrived for [debounceMs] (at most [maxWaitMs] in total) and
 * then makes one scheduling decision. A request during a decision schedules once more, so none is
 * lost. A user request ends the wait immediately and survives a failed decision.
 */
internal class SubscriptionSyncScheduler(scope:CoroutineScope,private val debounceMs:Long=300,private val maxWaitMs:Long=2_000,
    private val clock:()->Long={ System.nanoTime()/1_000_000 },private val failed:(Exception)->Unit={},private val schedule:suspend (user:Boolean)->Unit) {
    private val requests=Channel<Unit>(Channel.CONFLATED)
    private val user=AtomicBoolean(false)
    init {
        scope.launch {
            for(first in requests) {
                val started=clock()
                while(!user.get()) {
                    val remaining=maxWaitMs-(clock()-started)
                    if(remaining<=0 || withTimeoutOrNull(minOf(debounceMs,remaining)) { requests.receive() }==null)break
                }
                requests.tryReceive()
                val requested=user.getAndSet(false)
                try { schedule(requested) }
                catch(error:Exception) {
                    // A cancelled WorkManager future is also a CancellationException; only our own cancellation ends the loop.
                    if(error is CancellationException)currentCoroutineContext().ensureActive()
                    if(requested)user.set(true)
                    // Reporting must never end the consumer.
                    try { failed(error) } catch(_:Exception) {}
                }
            }
        }
    }
    fun kick() { requests.trySend(Unit) }
    fun syncNow() { user.set(true);requests.trySend(Unit) }
}
