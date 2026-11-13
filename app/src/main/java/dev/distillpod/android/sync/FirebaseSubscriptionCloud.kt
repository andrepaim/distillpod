package dev.distillpod.android.sync

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import dev.distillpod.core.sync.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.util.UUID

/** Uses server transactions only. Room owns the durable offline queue. */
class FirebaseSubscriptionCloud(private val auth:FirebaseAuth,private val firestore:FirebaseFirestore,private val artifacts:ArtifactSync?=null):SubscriptionCloud,SyncHistoryCloud {
    override suspend fun prepareContent(owner:SyncOwner,record:CloudSubscription) {
        (artifacts?:error("Cloud content storage is unavailable in this build")).fetch(owner,record)
    }
    override val uid get()=auth.currentUser?.uid
    private fun account(expected:String?=uid):DocumentReference {
        check(expected!=null && uid==expected) { "Reconnect your sync account." }
        return firestore.collection("users").document(expected)
    }
    override suspend fun head():CloudHead {
        val user=uid?:error("Reconnect your sync account.")
        val root=account(user)
        return firestore.runTransaction { tx ->
            val snapshot=tx.get(root)
            if(!snapshot.exists()) {
                val generation=UUID.randomUUID().toString()
                tx.set(root,mapOf("generation" to generation,"sequence" to 0L,"active" to true,"protocol" to 1L))
                CloudHead(SyncOwner(user,generation),0)
            } else {
                check(snapshot.getBoolean("active")==true && snapshot.getLong("protocol") in KNOWN_PROTOCOLS) { "Cloud account is unavailable or needs an app update." }
                CloudHead(SyncOwner(user,snapshot.getString("generation")?:error("Invalid cloud account")),snapshot.getLong("sequence")?:error("Invalid cloud sequence"),
                    (snapshot.getLong("protocol")?:1).toInt(),snapshot.getLong("compactedThrough")?:0)
            }
        }.await()
    }
    suspend fun enableFullScope() {
        val root=account()
        firestore.runTransaction {tx->
            val current=tx.get(root)
            check(current.getBoolean("active")==true)
            check(current.getLong("protocol") in KNOWN_PROTOCOLS)
            if(current.getLong("protocol")==1L)tx.update(root,"protocol",2L)
        }.await()
    }
    suspend fun enableContextualMoments() {
        val root=account()
        firestore.runTransaction { tx ->
            val current=tx.get(root)
            check(current.getBoolean("active")==true && current.getLong("protocol") in KNOWN_PROTOCOLS)
            // Never lowers a compacting (protocol 4) account.
            if(current.getLong("protocol")!!<3L)tx.update(root,"protocol",3L)
        }.await()
    }
    override suspend fun entities(after:String):List<CloudSubscription> {
        var query=account().collection("entities").orderBy(FieldPath.documentId()).limit(100)
        if(after.isNotEmpty())query=query.startAfter(after)
        return query.get(Source.SERVER).await().documents.map { parse(it.id,it) }
    }
    override suspend fun changes(after:Long,through:Long):List<CloudSubscription> = account().collection("changes")
        .whereGreaterThan("sequence",after).whereLessThanOrEqualTo("sequence",through).orderBy("sequence").limit(100)
        .get(Source.SERVER).await().documents.map { parse(it.getString("key")?:error("Invalid cloud change"),it) }
    override suspend fun commit(operation:SyncOutboxEntity):CloudSubscription {
        val root=account(operation.uid)
        val payload=commitPayload(operation)
        if(operation.entityKey.startsWith("artifact_")) {
            val content=artifacts?:error("Cloud content storage is unavailable in this build")
            uploadContent(operation,{content.upload(operation)}) {root.collection("receipts").document(operation.operationId).get(Source.SERVER).await().exists()}
        }
        val canonical=JSONObject().put("key",operation.entityKey).put("baseVersion",operation.baseVersion)
            .put("payload",JSONObject(payload.toSortedMap())).put("deleted",operation.deleted).put("generation",operation.generation).toString()
        var observed:Long?=null
        var observedProtocol:Long?=null
        // Whether this attempt's transaction reached its writes; a read denial is an account problem.
        var staged=false
        repeat(5) { attempt ->
            account(operation.uid)
            staged=false
            try {
                return firestore.runTransaction { tx ->
                    staged=false
                    val entity=root.collection("entities").document(operation.entityKey)
                    val receipt=root.collection("receipts").document(operation.operationId)
                    val h=tx.get(root);val old=tx.get(entity);val previous=tx.get(receipt)
                    check(h.getBoolean("active")==true && h.getString("generation")==operation.generation) { "Cloud account changed." }
                    observed=h.getLong("sequence")?:error("Invalid cloud sequence")
                    observedProtocol=h.getLong("protocol")
                    val protocol=(observedProtocol?:1).toInt()
                    if(previous.exists()) {
                        check(SyncCompaction.receiptMatches(previous.getString("signature"),canonical)) { "Operation identity mismatch." }
                        return@runTransaction CloudSubscription(operation.entityKey,previous.getLong("version")?:error("Invalid receipt"),
                            if(operation.deleted)emptyMap() else payload,operation.deleted,previous.getLong("sequence")?:error("Invalid receipt"))
                    }
                    if((old.getLong("version")?:0)!=operation.baseVersion) {
                        val current=if(old.exists())parse(old.id,old) else null
                        // This operation's receipt may have been compacted after an earlier attempt committed it.
                        if(receiptlessRetry(operation,h.getLong("compactedThrough")?:0,current))return@runTransaction current!!
                        throw SubscriptionConflict(current)
                    }
                    val sequence=observed!!+1
                    staged=true
                    val data=mapOf("version" to operation.baseVersion+1,"payload" to if(operation.deleted)emptyMap() else payload,
                        "deleted" to operation.deleted,"generation" to operation.generation,"sequence" to sequence,"operationId" to operation.operationId)
                    tx.update(root,"sequence",sequence);tx.set(entity,data)
                    tx.set(root.collection("changes").document(sequence.toString()),data+mapOf("key" to operation.entityKey))
                    tx.set(receipt,mapOf("signature" to SyncCompaction.receiptSignature(canonical,protocol),"key" to operation.entityKey,"version" to operation.baseVersion+1,"sequence" to sequence,"generation" to operation.generation))
                    CloudSubscription(operation.entityKey,operation.baseVersion+1,if(operation.deleted)emptyMap() else payload,operation.deleted,sequence)
                }.await()
            } catch(error:Exception) {
                generateSequence<Throwable>(error){it.cause}.filterIsInstance<SubscriptionConflict>().firstOrNull()?.let { throw it }
                if(firestoreWriteRejected(error,staged))throw SyncOperationRejected(error)
                if(error !is FirebaseFirestoreException || error.code!=FirebaseFirestoreException.Code.PERMISSION_DENIED || observed==null || attempt==4)throw error
                val latest=root.get(Source.SERVER).await()
                if(latest.getBoolean("active")!=true || latest.getString("generation")!=operation.generation)throw error
                // No competing commit advanced the head and no compaction upgrade changed the receipt format:
                // the rules refused this operation's own writes.
                if((latest.getLong("sequence")?:0)<=observed!! && latest.getLong("protocol")==observedProtocol)throw if(staged)SyncOperationRejected(error) else error
            }
        }
        error("Cloud transaction did not complete")
    }
    override suspend fun upgradeForCompaction() {
        val root=account()
        firestore.runTransaction { tx ->
            val current=tx.get(root)
            check(current.getBoolean("active")==true && current.getLong("protocol") in setOf(3L,4L)) { "Cloud account cannot be compacted." }
            if(current.getLong("protocol")==3L)tx.update(root,"protocol",SyncCompaction.PROTOCOL.toLong())
        }.await()
    }
    override suspend fun advanceCompaction(through:Long) {
        val root=account()
        firestore.runTransaction { tx ->
            val current=tx.get(root)
            check(current.getBoolean("active")==true && current.getLong("protocol")==SyncCompaction.PROTOCOL.toLong()) { "Cloud account cannot be compacted." }
            val head=current.getLong("sequence")?:error("Invalid cloud sequence")
            check(through<=head-SyncCompaction.RETAINED_SEQUENCES) { "Compaction would remove retained history." }
            if(through>(current.getLong("compactedThrough")?:0))tx.update(root,"compactedThrough",through)
        }.await()
    }
    override suspend fun compactChanges(through:Long,limit:Int)=delete(account().collection("changes"),through,limit)
    override suspend fun compactReceipts(through:Long,limit:Int)=delete(account().collection("receipts"),through,limit)
    /** Oldest first, so an interrupted run resumes. Small batches keep each write within the rules' document-access limits. */
    private suspend fun delete(history:CollectionReference,through:Long,limit:Int):Int {
        val doomed=history.whereLessThanOrEqualTo("sequence",through).orderBy("sequence").limit(limit.toLong()).get(Source.SERVER).await().documents
        for(chunk in doomed.chunked(10)) {
            val batch=firestore.batch()
            chunk.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
        return doomed.size
    }
    private fun parse(key:String,snapshot:DocumentSnapshot):CloudSubscription {
        val payload=snapshot.get("payload") as? Map<*,*> ?: error("Invalid cloud payload")
        val fields=payload.entries.associate { (k,v)->require(k is String && v is String);k to v }
        return CloudSubscription(key,snapshot.getLong("version")?:error("Invalid cloud version"),fields,
            snapshot.getBoolean("deleted")?:error("Invalid deletion state"),snapshot.getLong("sequence")?:error("Invalid cloud sequence"))
    }
}

private val KNOWN_PROTOCOLS=(1L..SyncCompaction.PROTOCOL.toLong()).toSet()

/** The operation's payload, validated as the protocol requires before any cloud write; an invalid stored operation never becomes valid. */
internal fun commitPayload(operation:SyncOutboxEntity):Map<String,String> {
    val payload=SubscriptionSyncStore.decode(operation.payload)
    try {
        SyncProtocol.validate(SyncMutation(operation.operationId,SyncOwner(operation.uid,operation.generation),operation.entityKey,operation.baseVersion,payload,operation.deleted))
    } catch(invalid:IllegalArgumentException) { throw SyncOperationRejected(invalid) }
    return payload
}

/**
 * Uploads an operation's content. An attempt that reached upload before may already be published:
 * its receipt then completes the commit, so a refusal of the content stands only without one.
 */
internal suspend fun uploadContent(operation:SyncOutboxEntity,upload:suspend ()->Unit,published:suspend ()->Boolean) {
    try { upload() }
    catch(refused:SyncOperationRejected) { if(operation.state!="uploading" || !published())throw refused }
}
