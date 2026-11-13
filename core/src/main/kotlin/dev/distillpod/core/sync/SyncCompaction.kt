package dev.distillpod.core.sync

/**
 * Account protocol 4: history at or below a watermark may be deleted (issue #176).
 * Entities stay complete, so an installation whose cursor fell below the watermark rebuilds from them.
 */
object SyncCompaction {
    const val PROTOCOL=4
    /** Equal to retainedSequences() in firebase/firestore.rules. */
    const val RETAINED_SEQUENCES=10_000L
    const val RETAINED_MS=30L*24*60*60*1000
    const val BATCH=200
    const val INTERVAL_MS=60L*60*1000

    /** Protocol 4 stores only the SHA-256 of the canonical signature; earlier receipts keep the full text. */
    fun receiptSignature(canonical:String,protocol:Int):String = if(protocol>=PROTOCOL) sha256(canonical) else canonical
    /** A stored receipt identifies the same operation in either form. */
    fun receiptMatches(stored:String?,canonical:String):Boolean = stored!=null && (stored==canonical || stored==sha256(canonical))

    /** This installation's own observation: [sequence] was the head at [at]; history through [eligible] is old enough. */
    data class Mark(val sequence:Long,val at:Long,val eligible:Long=0)
    /**
     * Rolls the observation forward. History through a head seen at least [retainedMs] ago becomes eligible.
     * A clock that went backwards restarts the wait; a head below the mark is a different history and trusts nothing.
     */
    fun observe(mark:Mark?,head:Long,now:Long,retainedMs:Long=RETAINED_MS):Mark = when {
        mark==null || head<mark.sequence -> Mark(head,now,0)
        now<mark.at -> Mark(head,now,mark.eligible)
        now-mark.at>=retainedMs -> Mark(head,now,maxOf(mark.eligible,mark.sequence))
        else -> mark
    }
    /**
     * The new watermark, or null when it cannot rise: it keeps the larger of the sequence and age windows and
     * never passes what this installation itself has replayed.
     */
    fun watermark(head:Long,current:Long,eligible:Long,cursor:Long,retainedSequences:Long=RETAINED_SEQUENCES):Long? =
        minOf(head-retainedSequences,eligible,cursor).takeIf { it>0 && it>current }
    /** Whether the cloud already holds exactly what an operation based on [baseVersion] would have written. */
    fun alreadyApplied(baseVersion:Long,deleted:Boolean,payload:Map<String,String>,current:RemoteRecord?):Boolean =
        current!=null && current.version>baseVersion && current.deleted==deleted && current.payload==(if(deleted) emptyMap() else payload)

    private fun sha256(value:String)=java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
