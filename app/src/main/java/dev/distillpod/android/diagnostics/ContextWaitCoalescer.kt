package dev.distillpod.android.diagnostics

import dev.distillpod.core.TimeRange
import dev.distillpod.core.normalizeRanges
import java.util.UUID

/** Local numeric inputs only; never serialized, hashed or retained beyond the bounded wait cache. */
@ConsistentCopyVisibility
internal data class ContextWaitInput private constructor(
    val captureStart:Long, val captureEnd:Long, val window:TimeRange, val duration:Long,
    val dependencyRevision:String?, val coverage:List<TimeRange>
) {
    companion object {
        const val MAX_RANGES=64
        fun create(captureStart:Long,captureEnd:Long,window:TimeRange,duration:Long,
            dependencyRevision:String?,coverage:List<TimeRange>):ContextWaitInput? {
            if(captureStart<0 || captureEnd<captureStart || duration<0)return null
            if(dependencyRevision!=null && !randomId(dependencyRevision))return null
            val relevant=normalizeRanges(coverage,Long.MAX_VALUE).mapNotNull {
                val start=maxOf(it.startMs,window.startMs);val end=minOf(it.endMs,window.endMs)
                if(end>start)TimeRange(start,end) else null
            }
            if(relevant.size>MAX_RANGES)return null
            return ContextWaitInput(captureStart,captureEnd,window,duration,dependencyRevision,relevant)
        }
        internal fun randomId(value:String)=runCatching {
            UUID.fromString(value).let {it.version()==4 && it.toString()==value}
        }.getOrDefault(false)
    }
}

/** Used only under Diagnostics' consent lock. This bounds memory, not admission volume. */
internal class ContextWaitCoalescer(private val maxEntries:Int=DiagnosticBudget.Lane.CRITICAL.limit) {
    private data class Key(val moment:String,val attempt:String,val execution:String)
    private data class Snapshot(val input:ContextWaitInput,val fields:Map<String,Any>)
    private val waits=LinkedHashMap<Key,Snapshot>(16,0.75f,true)
    private var scope:Pair<String?,Long>?=null
    init {require(maxEntries>0)}
    internal val size get()=waits.size
    fun clear() {waits.clear();scope=null}

    fun record(operation:String,evidence:ContextPreparationEvidence,correlation:DiagnosticCorrelation?,
        epoch:String?,day:Long,write:()->Boolean) {
        val current=epoch to day
        if(scope!=current) {waits.clear();scope=current}
        val key=correlation?.let {
            if(it.momentId!=operation || !ContextWaitInput.randomId(operation))null
            else if(it.generationAttemptId==null || it.executionId==null)null
            else Key(operation,it.generationAttemptId,it.executionId)
        }
        if(key==null) {write();return}
        val input=evidence.waitInput
        val snapshot=if(evidence.outcome=="transcript_required" && input!=null)
            Snapshot(input,evidence.fields(0)+correlation?.fields().orEmpty()) else null
        if(snapshot!=null && waits[key]==snapshot)return
        // A changed/terminal observation invalidates old evidence even if its own write fails.
        waits.remove(key)
        if(write() && snapshot!=null) {
            waits[key]=snapshot
            while(waits.size>maxEntries)waits.remove(waits.keys.first())
        }
    }
}
