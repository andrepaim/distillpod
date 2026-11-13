package dev.distillpod.android.sync

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import dev.distillpod.core.sync.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.util.UUID

/** Uses server transactions only. Room owns the durable offline queue. */
class FirebaseSubscriptionCloud(private val auth:FirebaseAuth,private val firestore:FirebaseFirestore,private val artifacts:ArtifactSync?=null):SubscriptionCloud {
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
                check(snapshot.getBoolean("active")==true && snapshot.getLong("protocol") in setOf(1L,2L,3L)) { "Cloud account is unavailable or needs an app update." }
                CloudHead(SyncOwner(user,snapshot.getString("generation")?:error("Invalid cloud account")),snapshot.getLong("sequence")?:error("Invalid cloud sequence"),(snapshot.getLong("protocol")?:1).toInt())
            }
        }.await()
    }
    suspend fun enableFullScope() {
        val root=account()
        firestore.runTransaction {tx->
            val current=tx.get(root)
            check(current.getBoolean("active")==true)
            check(current.getLong("protocol") in setOf(1L,2L,3L))
            if(current.getLong("protocol")==1L)tx.update(root,"protocol",2L)
        }.await()
    }
    suspend fun enableContextualMoments() {
        val root=account()
        firestore.runTransaction { tx ->
            val current=tx.get(root)
            check(current.getBoolean("active")==true && current.getLong("protocol") in setOf(1L,2L,3L))
            if(current.getLong("protocol")!=3L)tx.update(root,"protocol",3L)
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
        val payload=SubscriptionSyncStore.decode(operation.payload)
        SyncProtocol.validate(SyncMutation(operation.operationId,SyncOwner(operation.uid,operation.generation),operation.entityKey,operation.baseVersion,payload,operation.deleted))
        if(operation.entityKey.startsWith("artifact_"))(artifacts?:error("Cloud content storage is unavailable in this build")).upload(operation)
        val signature=JSONObject().put("key",operation.entityKey).put("baseVersion",operation.baseVersion)
            .put("payload",JSONObject(payload.toSortedMap())).put("deleted",operation.deleted).put("generation",operation.generation).toString()
        var observed:Long?=null
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
                    if(previous.exists()) {
                        check(previous.getString("signature")==signature) { "Operation identity mismatch." }
                        return@runTransaction CloudSubscription(operation.entityKey,previous.getLong("version")?:error("Invalid receipt"),
                            if(operation.deleted)emptyMap() else payload,operation.deleted,previous.getLong("sequence")?:error("Invalid receipt"))
                    }
                    if((old.getLong("version")?:0)!=operation.baseVersion)throw SubscriptionConflict(if(old.exists())parse(old.id,old) else null)
                    val sequence=observed!!+1
                    staged=true
                    val data=mapOf("version" to operation.baseVersion+1,"payload" to if(operation.deleted)emptyMap() else payload,
                        "deleted" to operation.deleted,"generation" to operation.generation,"sequence" to sequence,"operationId" to operation.operationId)
                    tx.update(root,"sequence",sequence);tx.set(entity,data)
                    tx.set(root.collection("changes").document(sequence.toString()),data+mapOf("key" to operation.entityKey))
                    tx.set(receipt,mapOf("signature" to signature,"key" to operation.entityKey,"version" to operation.baseVersion+1,"sequence" to sequence,"generation" to operation.generation))
                    CloudSubscription(operation.entityKey,operation.baseVersion+1,if(operation.deleted)emptyMap() else payload,operation.deleted,sequence)
                }.await()
            } catch(error:Exception) {
                generateSequence<Throwable>(error){it.cause}.filterIsInstance<SubscriptionConflict>().firstOrNull()?.let { throw it }
                if(error !is FirebaseFirestoreException || error.code!=FirebaseFirestoreException.Code.PERMISSION_DENIED || observed==null || attempt==4)throw error
                val latest=root.get(Source.SERVER).await()
                if(latest.getBoolean("active")!=true || latest.getString("generation")!=operation.generation)throw error
                // No competing commit advanced the head: the rules refused this operation's own writes.
                if((latest.getLong("sequence")?:0)<=observed!!)throw if(staged)SyncOperationRejected(error) else error
            }
        }
        error("Cloud transaction did not complete")
    }
    private fun parse(key:String,snapshot:DocumentSnapshot):CloudSubscription {
        val payload=snapshot.get("payload") as? Map<*,*> ?: error("Invalid cloud payload")
        val fields=payload.entries.associate { (k,v)->require(k is String && v is String);k to v }
        return CloudSubscription(key,snapshot.getLong("version")?:error("Invalid cloud version"),fields,
            snapshot.getBoolean("deleted")?:error("Invalid deletion state"),snapshot.getLong("sequence")?:error("Invalid cloud sequence"))
    }
}
