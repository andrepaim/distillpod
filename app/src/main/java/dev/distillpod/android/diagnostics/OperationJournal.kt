package dev.distillpod.android.diagnostics

import java.io.*
import java.util.UUID

/** Content-free, bounded journal. Stable revision IDs make replay detectable downstream. */
internal class OperationJournal(private val file: File, private val now: () -> Long = System::currentTimeMillis,
    private val monotonic: () -> Long = { System.nanoTime()/1_000_000 }, private val release:String="unknown") {
    data class Row(val id:String, val kind:String, val operation:String, val attempt:String, val state:String,
        val reason:String, val source:String, val started:Long, val updated:Long, val elapsed:Long,
        val sequence:Int, val release:String, val delivered:Boolean = false)
    private val rows=mutableListOf<Row>()
    private val clocks=mutableMapOf<String,Long>()
    private var nextSequence=0
    var dropped=0L; private set
    init {
        if(file.exists()) try { DataInputStream(FileInputStream(file)).use { input ->
            require(input.readInt()==1);dropped=input.readLong()
            val size=input.readInt();require(size in 0..500)
            repeat(size) { rows+=Row(input.readUTF(),input.readUTF(),input.readUTF(),input.readUTF(),input.readUTF(),input.readUTF(),input.readUTF(),input.readLong(),input.readLong(),input.readLong(),input.readInt(),input.readUTF(),input.readBoolean()) }
            nextSequence=rows.maxOfOrNull {it.sequence} ?: 0
            if(input.available()>=4)nextSequence=maxOf(nextSequence,input.readInt())
        } } catch(_:Exception) { rows.clear();dropped++ }
    }
    @Synchronized fun transition(kind:String, operation:String, state:String, reason:String="none", source:String="background", attempt:String?=null) {
        require(kind in KINDS && state in STATES && source in SOURCES && reason.matches(Regex("[a-z_]{1,40}")))
        UUID.fromString(operation);attempt?.let {UUID.fromString(it)}
        val old=rows.lastOrNull {it.kind==kind && it.operation==operation}
        if(old?.state==state && state in TERMINAL)return
        // A repeated callback is not another attempt. Explicit starts after failure are retries.
        if(old?.state==state && old.reason==reason && (attempt==null || old.attempt==attempt))return
        if(old?.state=="cancelled" && state in setOf("completed","failed","interrupted"))return
        if(old?.state=="completed" && !(state=="running" && attempt!=null && attempt!=old.attempt))return
        val newAttempt=old==null || state=="running" && (old.state in TERMINAL || attempt!=null && attempt!=old.attempt)
        val attemptId=if(newAttempt) attempt ?: UUID.randomUUID().toString() else old!!.attempt
        val key="$kind:$operation:$attemptId"
        val elapsed=clocks[key]?.let {(monotonic()-it).coerceAtLeast(0)} ?: -1L
        if(newAttempt)clocks[key]=monotonic()
        rows+=Row(UUID.randomUUID().toString(),kind,operation,attemptId,state,reason,old?.source?.takeUnless {it=="background"} ?: source,
            old?.started ?: now(),now(),if(newAttempt)0 else elapsed,Math.incrementExact(nextSequence).also {nextSequence=it},release)
        prune();save()
    }
    @Synchronized fun pending():List<Row> {prune();return rows.filterNot {it.delivered}.toList()}
    @Synchronized fun latest():List<Row> = rows.groupBy {it.kind to it.operation}.values.map {it.last()}
    @Synchronized fun acknowledge(id:String) {val i=rows.indexOfFirst {it.id==id};if(i>=0){rows[i]=rows[i].copy(delivered=true);save()}}
    @Synchronized fun clear() {rows.clear();clocks.clear();dropped=0;runCatching {save()}.onFailure {file.delete()}}
    @Synchronized fun recover(resolve:(String,String)->String?) {
        latest().filter {it.state !in TERMINAL}.forEach {
            val state=resolve(it.kind,it.operation) ?: "unknown"
            transition(it.kind,it.operation,state,if(state=="completed")"reconciled" else "process_interrupted",it.source)
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
            val out=DataOutputStream(stream);out.writeInt(1);out.writeLong(dropped);out.writeInt(rows.size)
            rows.forEach {r->listOf(r.id,r.kind,r.operation,r.attempt,r.state,r.reason,r.source).forEach(out::writeUTF)
                out.writeLong(r.started);out.writeLong(r.updated);out.writeLong(r.elapsed);out.writeInt(r.sequence);out.writeUTF(r.release);out.writeBoolean(r.delivered)}
            // A content-free scalar survives pruning/revocation so later outcomes
            // cannot sort behind earlier reports already retained by Sentry.
            out.writeInt(nextSequence)
            out.flush();stream.fd.sync()
        };check(tmp.renameTo(file))
    }
    companion object {
        val KINDS=setOf("capture","transcription","distillation","playback","voice")
        val TERMINAL=setOf("completed","failed","cancelled","interrupted","unknown")
        val STATES=TERMINAL+setOf("queued","running","waiting","paused")
        val SOURCES=setOf("background","phone","headphones","car")
    }
}
