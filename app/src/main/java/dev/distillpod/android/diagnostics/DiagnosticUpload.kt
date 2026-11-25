package dev.distillpod.android.diagnostics

import android.content.SharedPreferences
import java.io.File

/**
 * Delivery bookkeeping for the diagnostics outbox (#300). It holds only counts, times and the
 * outbox's own file names; nothing here is sent except the numeric [fields].
 */
internal class DiagnosticUploadState(private val prefs:SharedPreferences, private val now:()->Long=System::currentTimeMillis) {
    val consecutiveFailures get()=prefs.getInt(FAILURES,0)
    /** Time of the last acknowledged envelope, 0 if none since this was introduced. */
    val lastUpload get()=prefs.getLong(LAST_UPLOAD,0)

    /** Remaining server-requested pause. An older version may have stored up to a day: clamp it once. */
    fun pausedForMs():Long {
        val until=prefs.getLong(RETRY_AFTER,0)
        val limit=now()+MAX_PAUSE_MS
        if(until>limit) {prefs.edit().putLong(RETRY_AFTER,limit).apply();return MAX_PAUSE_MS}
        return (until-now()).coerceAtLeast(0)
    }
    fun pause(seconds:Long) {
        val ms=(seconds*1000).coerceIn(MIN_PAUSE_MS,MAX_PAUSE_MS)
        prefs.edit().putLong(RETRY_AFTER,now()+ms).putLong(RATE_LIMITED,prefs.getLong(RATE_LIMITED,0)+1).apply()
    }

    /** Called before the first send of a run, with the outbox as it then was. */
    fun begin(files:List<File>) {
        val wait=oldestAge(files,now())
        prefs.edit().putLong(RUN_WAIT,wait).putInt(RUN_PENDING,files.size).putInt(RUN_FAILURES,consecutiveFailures).apply()
    }
    fun accepted() {
        val edit=prefs.edit().putLong(LAST_UPLOAD,now()).putInt(FAILURES,0).remove(FAILING_SINCE)
        val wait=prefs.getLong(RUN_WAIT,0)
        // The first acknowledgement after a long wait describes the stall; later ones in the run do not.
        if(wait>=STALL_MS) edit.putBoolean(STALL,true).putLong(STALL_WAIT,wait)
            .putInt(STALL_FAILURES,prefs.getInt(RUN_FAILURES,0)).putInt(STALL_PENDING,prefs.getInt(RUN_PENDING,0))
        edit.putLong(RUN_WAIT,0).apply()
    }
    fun failed() {
        val edit=prefs.edit().putInt(FAILURES,consecutiveFailures+1)
        if(!prefs.contains(FAILING_SINCE))edit.putLong(FAILING_SINCE,now())
        edit.apply()
    }
    fun rejected() {prefs.edit().putLong(REJECTED,prefs.getLong(REJECTED,0)+1).apply()}

    /**
     * Counts an envelope that failed while a later one in the same run was accepted. Returns true
     * once it has failed that way [ISOLATED_FAILURE_LIMIT] times, when it is to be discarded.
     */
    fun isolatedFailure(file:File, existing:Set<String>):Boolean {
        val counts=blame().filterKeys {it in existing}.toMutableMap()
        val count=(counts[file.name] ?: 0)+1
        val discard=count>=ISOLATED_FAILURE_LIMIT
        if(discard)counts.remove(file.name) else counts[file.name]=count
        val edit=prefs.edit().putStringSet(BLAME,counts.entries.take(MAX_BLAMED).map {"${it.value}|${it.key}"}.toSet())
        if(discard)edit.putLong(DISCARDED,prefs.getLong(DISCARDED,0)+1)
        edit.apply()
        return discard
    }
    private fun blame():Map<String,Int> = prefs.getStringSet(BLAME,emptySet()).orEmpty().mapNotNull {
        val count=it.substringBefore('|').toIntOrNull() ?: return@mapNotNull null
        it.substringAfter('|') to count
    }.toMap()

    /** Bounded exponential delay after failed attempts: 30 s, 1 min, 2 min … at most [MAX_RETRY_DELAY_MS]. */
    fun retryDelayMs():Long {
        val failures=consecutiveFailures.coerceAtLeast(1)
        return (BASE_RETRY_DELAY_MS shl (failures-1).coerceAtMost(10)).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    /** The stall that ended at the last resumed delivery, until [stallReported]. */
    fun pendingStallReport():Map<String,Any>? = if(!prefs.getBoolean(STALL,false)) null else mapOf(
        "upload_stall_wait_ms" to prefs.getLong(STALL_WAIT,0),"upload_stall_failures" to prefs.getInt(STALL_FAILURES,0),
        "upload_stall_pending" to prefs.getInt(STALL_PENDING,0))
    fun stallReported() {prefs.edit().remove(STALL).remove(STALL_WAIT).remove(STALL_FAILURES).remove(STALL_PENDING).apply()}

    /** Content-free numbers for diagnostics.health. */
    fun fields(files:List<File>, at:Long=now()):Map<String,Any> = buildMap {
        put("oldest_pending_age_ms",oldestAge(files,at))
        put("upload_failures",consecutiveFailures)
        put("upload_failing_ms",prefs.getLong(FAILING_SINCE,0).takeIf {it>0}?.let {(at-it).coerceAtLeast(0)} ?: 0L)
        put("last_upload_age_ms",lastUpload.takeIf {it>0}?.let {(at-it).coerceAtLeast(0)} ?: -1L)
        put("upload_paused_ms",(prefs.getLong(RETRY_AFTER,0)-at).coerceIn(0,MAX_PAUSE_MS))
        put("upload_rejected",prefs.getLong(REJECTED,0))
        put("upload_discarded",prefs.getLong(DISCARDED,0))
        put("upload_rate_limited",prefs.getLong(RATE_LIMITED,0))
        pendingStallReport()?.let {putAll(it)}
    }

    /** One line for Settings › Diagnostics. */
    fun summary(files:List<File>, at:Long=now()):String = buildList {
        if(files.isNotEmpty())add("oldest pending report ${age(oldestAge(files,at))} old")
        consecutiveFailures.takeIf {it>0}?.let {add("$it failed upload attempt"+(if(it==1)"" else "s")+" in a row")}
        (prefs.getLong(RETRY_AFTER,0)-at).takeIf {it>0}?.let {add("Sentry asked to pause uploads for ${age(it.coerceAtMost(MAX_PAUSE_MS))}")}
    }.joinToString(" · ")

    /**
     * Forgets the delivery state of the cleared outbox when collection ends. A server-requested
     * pause and the cumulative counters are kept.
     */
    fun clear() {prefs.edit().apply {KEYS.forEach {remove(it)}}.apply()}

    companion object {
        const val MIN_PAUSE_MS=30_000L
        const val MAX_PAUSE_MS=60*60_000L
        const val BASE_RETRY_DELAY_MS=30_000L
        const val MAX_RETRY_DELAY_MS=15*60_000L
        /** A report that waited this long before an acknowledgement is reported as a stall. */
        const val STALL_MS=60*60_000L
        const val ISOLATED_FAILURE_LIMIT=3
        private const val MAX_BLAMED=50
        private const val RETRY_AFTER="retry_after"
        private const val FAILURES="upload_failures"
        private const val FAILING_SINCE="upload_failing_since"
        private const val LAST_UPLOAD="last_upload_ms"
        private const val REJECTED="upload_rejected"
        private const val DISCARDED="upload_discarded"
        private const val RATE_LIMITED="upload_rate_limited"
        private const val BLAME="upload_blame"
        private const val RUN_WAIT="upload_run_wait"
        private const val RUN_PENDING="upload_run_pending"
        private const val RUN_FAILURES="upload_run_failures"
        private const val STALL="upload_stall"
        private const val STALL_WAIT="upload_stall_wait"
        private const val STALL_FAILURES="upload_stall_failures"
        private const val STALL_PENDING="upload_stall_pending"
        private val KEYS=listOf(FAILURES,FAILING_SINCE,BLAME,RUN_WAIT,RUN_PENDING,RUN_FAILURES,STALL,STALL_WAIT,STALL_FAILURES,STALL_PENDING)
        fun oldestAge(files:List<File>, at:Long):Long = files.minOfOrNull {it.lastModified()}?.let {(at-it).coerceAtLeast(0)} ?: 0L
        private fun age(ms:Long):String = when {
            ms<60_000 -> "under a minute"
            ms<60*60_000 -> "${ms/60_000} min"
            else -> "${ms/(60*60_000)} h ${ms/60_000%60} min"
        }
    }
}

/**
 * Sends the outbox until it is empty, a run budget is spent, or delivery itself fails (#300).
 * It never asks WorkManager to retry: a backlog continues with a fresh request, and failures
 * wait a bounded delay, so neither request accumulates WorkManager's hours-long backoff.
 */
internal class DiagnosticUploader(private val queue:DiagnosticQueue, private val state:DiagnosticUploadState,
    private val send:(ByteArray)->DiagnosticDelivery.Receipt, private val now:()->Long=System::currentTimeMillis,
    private val budgetMs:Long=RUN_BUDGET_MS) {
    sealed interface Next {
        data object Done:Next
        data object Stopped:Next
        data class Continue(val delayMs:Long):Next
    }
    fun drain(active:()->Boolean):Next {
        state.pausedForMs().takeIf {it>0}?.let {return Next.Continue(it)}
        // Protected reports (errors, terminal summaries, health) first, then oldest first.
        val files=queue.files().sortedWith(compareBy<File> {!it.name.contains("priority-")}.thenBy {it.name})
        if(files.isEmpty())return Next.Done
        state.begin(files)
        val start=now()
        var delivered=false
        val failedThisRun=mutableListOf<File>()
        for(file in files) {
            if(!active())return Next.Stopped
            if(now()-start>=budgetMs)return Next.Continue(CONTINUE_DELAY_MS)
            val bytes=try {file.readBytes()} catch(_:java.io.IOException) {continue} // Expired or evicted meanwhile.
            val receipt=try {send(bytes)} catch(error:Exception) {
                if(error is kotlinx.coroutines.CancellationException)throw error
                null
            }
            when(receipt?.outcome) {
                DiagnosticDelivery.Outcome.ACCEPTED -> {
                    file.delete();state.accepted();delivered=true
                    // Earlier failures in this run were specific to those envelopes, not to delivery.
                    if(failedThisRun.isNotEmpty()) {
                        val existing=queue.files().map {it.name}.toSet()
                        failedThisRun.forEach {if(state.isolatedFailure(it,existing))it.delete()}
                        failedThisRun.clear()
                    }
                }
                DiagnosticDelivery.Outcome.REJECTED -> {file.delete();state.rejected()}
                else -> {
                    if(receipt?.retrySeconds!=null) {state.pause(receipt.retrySeconds);return Next.Continue(state.pausedForMs())}
                    state.failed()
                    // An authorization failure applies to every envelope; two failures in a row are taken
                    // to be the network or the server, not the envelopes.
                    if(receipt?.code in AUTH_FAILURES || failedThisRun.isNotEmpty())return Next.Continue(state.retryDelayMs())
                    failedThisRun+=file
                }
            }
            receipt?.retrySeconds?.let {state.pause(it);return Next.Continue(state.pausedForMs())}
        }
        if(failedThisRun.isNotEmpty() && !delivered)return Next.Continue(state.retryDelayMs())
        return if(queue.files().isEmpty())Next.Done else Next.Continue(CONTINUE_DELAY_MS)
    }
    companion object {
        /** Well inside WorkManager's ten-minute execution limit. */
        const val RUN_BUDGET_MS=4*60_000L
        const val CONTINUE_DELAY_MS=10_000L
        private val AUTH_FAILURES=setOf(401,403)
    }
}
