package dev.distillpod.android.diagnostics

import java.io.*
import java.util.UUID

/** Content-free, bounded journal. Stable revision IDs make replay detectable downstream. */
internal class OperationJournal(private val file: File, private val now: () -> Long = System::currentTimeMillis,
    private val monotonic: () -> Long = { System.nanoTime()/1_000_000 }, private val release:String="unknown") {
    data class Row(val id:String, val kind:String, val operation:String, val attempt:String, val state:String,
        val reason:String, val source:String, val started:Long, val updated:Long, val elapsed:Long,
        val sequence:Int, val release:String, val delivered:Boolean = false, val correlation:DiagnosticCorrelation? = null)
    private val rows=mutableListOf<Row>()
    private val clocks=mutableMapOf<String,Long>()
    private var nextSequence=0
    var dropped=0L; private set
    init {
        if(file.exists()) try { DataInputStream(FileInputStream(file)).use { input ->
            val version=input.readInt();require(version in 1..2);dropped=input.readLong()
            val size=input.readInt();require(size in 0..500)
            repeat(size) { rows+=Row(input.readUTF(),input.readUTF(),input.readUTF(),input.readUTF(),input.readUTF(),input.readUTF(),input.readUTF(),input.readLong(),input.readLong(),input.readLong(),input.readInt(),input.readUTF(),input.readBoolean(),if(version>=2 && input.readBoolean())DiagnosticCorrelation.read(input) else null) }
            nextSequence=rows.maxOfOrNull {it.sequence} ?: 0
            if(input.available()>=4)nextSequence=maxOf(nextSequence,input.readInt())
        } } catch(_:Exception) { rows.clear();dropped++ }
    }
    @Synchronized fun transition(kind:String, operation:String, state:String, reason:String="none", source:String="background", attempt:String?=null, correlation:DiagnosticCorrelation?=null, observedAt:Long=now()) {
        transitionRecord(kind,operation,state,reason,source,attempt,correlation?.copy(collectionEpoch=null),observedAt)
    }
    private fun transitionRecord(kind:String,operation:String,state:String,reason:String,source:String,attempt:String?,
        correlation:DiagnosticCorrelation?,observedAt:Long) {
        require(kind in KINDS && state in STATES && source in SOURCES && reason.matches(Regex("[a-z_]{1,40}")))
        UUID.fromString(operation);attempt?.let {UUID.fromString(it)}
        val old=rows.lastOrNull {it.kind==kind && it.operation==operation}
        // An uncorrelated legacy callback cannot be assigned to the latest run.
        if(old?.correlation!=null && correlation==null)return
        val changedExecution=correlation?.executionId!=null && old?.correlation?.executionId!=null &&
            correlation.executionId!=old.correlation.executionId
        val changedAttempt=correlation?.generationAttemptId!=null && old?.correlation?.generationAttemptId!=null &&
            correlation.generationAttemptId!=old.correlation.generationAttemptId
        // A late terminal callback from a superseded execution cannot terminate the current run.
        if((changedExecution || changedAttempt) && state in TERMINAL)return
        if(old?.state==state && state in TERMINAL && correlation==old.correlation)return
        // A repeated callback is not another attempt. Explicit starts after failure are retries.
        if(old?.state==state && old.reason==reason && (attempt==null || old.attempt==attempt) && correlation==old.correlation)return
        if(old?.state=="cancelled" && state in setOf("completed","failed","interrupted"))return
        if(old?.state=="completed" && !(state !in TERMINAL && (changedExecution || changedAttempt) || state=="running" && attempt!=null && attempt!=old.attempt))return
        val newAttempt=old==null || changedExecution || changedAttempt || state=="running" && (old.state in TERMINAL || attempt!=null && attempt!=old.attempt)
        val attemptId=if(newAttempt) correlation?.executionId ?: attempt ?: UUID.randomUUID().toString() else old!!.attempt
        val key="$kind:$operation:$attemptId"
        val elapsed=clocks[key]?.let {(monotonic()-it).coerceAtLeast(0)} ?: -1L
        if(newAttempt)clocks[key]=monotonic()
        rows+=Row(UUID.randomUUID().toString(),kind,operation,attemptId,state,reason,old?.source?.takeUnless {it=="background"} ?: source,
            old?.started ?: observedAt,observedAt,if(newAttempt)0 else elapsed,Math.incrementExact(nextSequence).also {nextSequence=it},release,correlation=correlation)
        prune();save()
    }
    @Synchronized fun pending():List<Row> {prune();return rows.filterNot {it.delivered}.toList()}
    @Synchronized fun latest():List<Row> = rows.groupBy {it.kind to it.operation}.values.map {it.last()}
    @Synchronized fun isPending(id:String):Boolean = rows.any {it.id==id && !it.delivered}
    @Synchronized fun acknowledge(id:String) {val i=rows.indexOfFirst {it.id==id};if(i>=0){rows[i]=rows[i].copy(delivered=true);save()}}
    @Synchronized fun clear() {rows.clear();clocks.clear();dropped=0;runCatching {save()}.onFailure {file.delete()}}
    @Synchronized fun recover(resolve:(String,String)->String?) {
        latest().filter {it.state !in TERMINAL}.forEach {
            val state=resolve(it.kind,it.operation) ?: "unknown"
            if(it.state=="running" && state=="queued")transition(it.kind,it.operation,"interrupted","process_interrupted",it.source,correlation=it.correlation)
            val reason=if(state==it.state)it.reason else if(state=="completed")"reconciled" else if(state=="partial")"partial" else "process_interrupted"
            transition(it.kind,it.operation,state,reason,it.source,correlation=it.correlation)
        }
    }
    private fun prune() {
        val expired=rows.filter {it.updated<now()-72*60*60*1000L}.toSet()
        dropped+=expired.count {!it.delivered};rows.removeAll(expired)
        while(rows.size>500){if(!rows.removeAt(0).delivered)dropped++}
        val active=rows.map {"${it.kind}:${it.operation}:${it.attempt}"}.toSet();clocks.keys.retainAll(active)
    }
    private fun save() {
        file.parentFile!!.mkdirs();val tmp=File(file.path+".tmp")
        FileOutputStream(tmp).use { stream ->
            val out=DataOutputStream(stream);out.writeInt(2);out.writeLong(dropped);out.writeInt(rows.size)
            rows.forEach {r->listOf(r.id,r.kind,r.operation,r.attempt,r.state,r.reason,r.source).forEach(out::writeUTF)
                out.writeLong(r.started);out.writeLong(r.updated);out.writeLong(r.elapsed);out.writeInt(r.sequence);out.writeUTF(r.release);out.writeBoolean(r.delivered);out.writeBoolean(r.correlation!=null);r.correlation?.write(out)}
            // A content-free scalar survives pruning/revocation so later outcomes
            // cannot sort behind earlier reports already retained by Sentry.
            out.writeInt(nextSequence)
            out.flush();stream.fd.sync()
        };check(tmp.renameTo(file))
    }
    companion object {
        val KINDS=setOf("capture","transcription","distillation","playback","voice")
        val TERMINAL=setOf("partial","completed","failed","cancelled","interrupted","unknown")
        val STATES=TERMINAL+setOf("queued","running","waiting","paused")
        val SOURCES=setOf("background","phone","headphones","car")
    }
}
