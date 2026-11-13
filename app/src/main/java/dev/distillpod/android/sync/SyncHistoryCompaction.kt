package dev.distillpod.android.sync

import dev.distillpod.android.data.AppDatabase
import dev.distillpod.core.sync.*
import kotlinx.coroutines.sync.Mutex

/** Cloud operations for protocol-4 history compaction; reading a compacted account needs none of them. */
interface SyncHistoryCloud {
    val uid:String?
    suspend fun head():CloudHead
    /** Protocol 3 → 4. Older clients then stop at their "update the app" gate instead of meeting a history gap. */
    suspend fun upgradeForCompaction()
    /** Raises the watermark; the rules keep it monotonic and at least [SyncCompaction.RETAINED_SEQUENCES] below the head. */
    suspend fun advanceCompaction(through:Long)
    /** Deletes up to [limit] of the oldest changes at or below [through]; returns how many. */
    suspend fun compactChanges(through:Long,limit:Int):Int
    /** Deletes up to [limit] of the oldest receipts at or below [through]; returns how many. */
    suspend fun compactReceipts(through:Long,limit:Int):Int
}

/** Off in builds unless `distillpodSyncCompaction=true`; tests inject shorter waits. */
data class SyncCompactionPolicy(val enabled:Boolean,val retainedSequences:Long=SyncCompaction.RETAINED_SEQUENCES,
    val retainedMs:Long=SyncCompaction.RETAINED_MS,val batch:Int=SyncCompaction.BATCH,val intervalMs:Long=SyncCompaction.INTERVAL_MS)

/**
 * Deletes history the account no longer needs, in bounded batches. The watermark trails the head by the larger of
 * the retained sequences and the retained age as observed by this installation, and never passes its own cursor.
 * Each run deletes from the oldest remaining documents, so an interrupted run simply resumes.
 */
class SyncHistoryCompactor(private val db:AppDatabase,private val cloud:SyncHistoryCloud,private val policy:SyncCompactionPolicy,
    private val now:()->Long=System::currentTimeMillis) {
    private val running=Mutex()
    /** Returns the number of history documents deleted; a run already in progress makes this one a no-op. */
    suspend fun run():Int {
        if(!policy.enabled || !running.tryLock())return 0
        try { return compact() } finally { running.unlock() }
    }
    private suspend fun compact():Int {
        val dao=db.sync()
        val b=dao.binding()?.takeIf { it.enabled && it.bootstrapped && it.uid==cloud.uid } ?: return 0
        val time=now()
        val last=dao.view(LAST_RUN)?.checkpointAt
        if(last!=null && time>=last && time-last<policy.intervalMs)return 0
        dao.putView(SyncViewEntity(LAST_RUN,checkpointAt=time))
        val head=cloud.head()
        // Protocol 4 implies the record scopes of protocols 2 and 3; upgrading earlier accounts would widen what they sync.
        if(head.owner!=SyncOwner(b.uid,b.generation) || head.protocol<3)return 0
        val previous=dao.view(MARK)?.let { SyncCompaction.Mark(it.sequence,it.checkpointAt,it.version) }
        val mark=SyncCompaction.observe(previous,head.sequence,time,policy.retainedMs)
        dao.putView(SyncViewEntity(MARK,version=mark.eligible,sequence=mark.sequence,checkpointAt=mark.at))
        var through=head.compactedThrough
        SyncCompaction.watermark(head.sequence,through,mark.eligible,b.cursor,policy.retainedSequences)?.let { target ->
            if(head.protocol<SyncCompaction.PROTOCOL)cloud.upgradeForCompaction()
            cloud.advanceCompaction(target)
            through=target
        }
        if(through<=0)return 0
        val changes=cloud.compactChanges(through,policy.batch)
        return changes+if(changes<policy.batch)cloud.compactReceipts(through,policy.batch-changes) else 0
    }
    private companion object {
        const val LAST_RUN="__compaction_run"
        /** version = eligible sequence, sequence/checkpointAt = observed head and when. */
        const val MARK="__compaction_mark"
    }
}

/**
 * A retried operation (an earlier attempt reached the cloud, state `uploading`) on an account whose receipts may be
 * compacted cannot prove it committed. When the cloud already holds exactly its result, it is treated as applied
 * instead of becoming a conflict. Accounts that never compacted keep every receipt, so this never applies to them.
 */
internal fun receiptlessRetry(operation:SyncOutboxEntity,compactedThrough:Long,current:CloudSubscription?):Boolean =
    operation.state=="uploading" && compactedThrough>0 && current!=null &&
        SyncCompaction.alreadyApplied(operation.baseVersion,operation.deleted,SubscriptionSyncStore.decode(operation.payload),
            RemoteRecord(current.version,current.payload,current.deleted))
