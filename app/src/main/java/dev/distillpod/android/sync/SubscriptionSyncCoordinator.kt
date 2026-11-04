package dev.distillpod.android.sync

import androidx.room.withTransaction
import dev.distillpod.android.data.AppDatabase
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.core.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class CloudHead(val owner:SyncOwner,val sequence:Long,val protocol:Int=1)
data class CloudSubscription(val key:String,val version:Long,val payload:Map<String,String>,val deleted:Boolean,val sequence:Long)
interface SubscriptionCloud {
    val uid:String?
    suspend fun prepareContent(owner:SyncOwner,record:CloudSubscription) {}
    suspend fun head():CloudHead
    suspend fun entities(after:String):List<CloudSubscription>
    suspend fun changes(after:Long,through:Long):List<CloudSubscription>
    suspend fun commit(operation:SyncOutboxEntity):CloudSubscription
}
class SubscriptionConflict(val current:CloudSubscription?):Exception("Subscription changed on another device")
class SubscriptionSyncCoordinator(private val db:AppDatabase,private val cloud:SubscriptionCloud) {
    private val dao get()=db.sync()
    private val mutex=Mutex()
    private suspend fun valid(b:SyncBindingEntity):Boolean {
        val now=dao.binding()
        return now?.enabled==true && now.lease==b.lease && now.uid==b.uid && now.generation==b.generation && cloud.uid==b.uid
    }
    private suspend fun update(b:SyncBindingEntity,block:(SyncBindingEntity)->SyncBindingEntity):Boolean=db.withTransaction {
        if(!valid(b)) return@withTransaction false
        dao.putBinding(block(dao.binding()!!));true
    }
    suspend fun run():Boolean = mutex.withLock {
        val b=dao.binding()?.takeIf { it.enabled && it.uid==cloud.uid } ?: return@withLock false
        try {
            val head=cloud.head()
            check(head.owner==SyncOwner(b.uid,b.generation)) { "Sync account changed; reconnect to review the library." }
            if(!valid(b)) return@withLock false
            if(!update(b){it.copy(status="syncing")})return@withLock false
            if(!dao.binding()!!.bootstrapped) {
                if(b.bootstrapAfter.isEmpty())update(b){it.copy(bootstrapHead=head.sequence)}
                repeat(5) {
                    val state=dao.binding()!!
                    if(!state.bootstrapped) {
                        val page=cloud.entities(state.bootstrapAfter)
                        if(!applyPage(b,page,bootstrap=true,done=page.size<100))return@withLock false
                    }
                }
                if(!dao.binding()!!.bootstrapped){update(b){it.copy(status="pending")};return@withLock false}
            }
            // Replay durable changes before pushing, keeping local edits above the remote base.
            var pages=0
            while(dao.binding()!!.cursor<head.sequence && pages++<5) {
                val cursor=dao.binding()!!.cursor
                val page=cloud.changes(cursor,head.sequence)
                check(page.isNotEmpty() && page.first().sequence==cursor+1 && page.zipWithNext().all { (a,c)->c.sequence==a.sequence+1 }) { "Cloud history gap; recovery is required." }
                if(!applyPage(b,page))return@withLock false
            }
            if(dao.binding()!!.cursor<head.sequence){update(b){it.copy(status="pending")};return@withLock false}
            repeat(20) {
                if(!valid(b))return@withLock false
                val op=dao.nextOperation(b.uid,b.generation) ?: return@repeat
                db.withTransaction { if(valid(b))dao.state(op.operationId,"uploading") }
                if(!valid(b))return@withLock false
                try {
                    val confirmed=cloud.commit(op)
                    if(!db.withTransaction {
                        if(!valid(b))return@withTransaction false
                        remember(b,confirmed)
                        dao.acknowledge(b.uid,b.generation,op.operationId)
                        dao.advanceOwnBase(b.uid,b.generation,op.entityKey,op.baseVersion,confirmed.version)
                        if(PortableRecord.isPortable(op.entityKey)) {
                            val view=dao.view(op.entityKey)
                            if(view!=null && view.version==op.baseVersion && (!op.entityKey.startsWith("progress_") || view.sessionId==confirmed.payload["sessionId"]))dao.putView(view.copy(version=confirmed.version))
                        }
                        if(dao.forKey(b.uid,b.generation,op.entityKey).isEmpty()) {
                            val latest=dao.remote(b.uid,b.generation,op.entityKey)!!
                            projectSubscription(db,CloudSubscription(latest.entityKey,latest.version,SubscriptionSyncStore.decode(latest.payload),latest.deleted,latest.sequence))
                        }
                        true
                    })return@withLock false
                } catch(conflict:SubscriptionConflict) {
                    if(!db.withTransaction {
                        if(!valid(b))return@withTransaction false
                        conflict.current?.let { remember(b,it) };dao.state(op.operationId,"conflict");true
                    })return@withLock false
                }
            }
            db.withTransaction {
                if(valid(b))applyStagedContent(db,b)
            }
            val contentPending=dao.artifacts(b.uid,b.generation).any {it.state in setOf("prepared","downloaded","waiting")}
            val remaining=dao.operations(b.uid,b.generation)
            val status=when {remaining.any { it.state=="conflict" }->"conflict";remaining.isNotEmpty() || contentPending->"pending";else->"synced"}
            update(b){it.copy(status=status,lastSyncedAt=if(status=="synced")System.currentTimeMillis() else it.lastSyncedAt)}
            status=="synced"
        } catch(cancelled:CancellationException){
            withContext(NonCancellable) { update(b){it.copy(status="pending")} }
            throw cancelled
        }
        catch(error:Exception){update(b){it.copy(status=if(error is java.io.IOException)"offline" else "error")};throw error}
    }
    private suspend fun applyPage(b:SyncBindingEntity,page:List<CloudSubscription>,bootstrap:Boolean=false,done:Boolean=false):Boolean {
        for(record in page) {
            validate(b,record)
            if(record.key.startsWith("artifact_"))cloud.prepareContent(SyncOwner(b.uid,b.generation),record)
            if(!valid(b))return false
        }
        return applyPreparedPage(b,page,bootstrap,done)
    }
    private suspend fun applyPreparedPage(b:SyncBindingEntity,page:List<CloudSubscription>,bootstrap:Boolean=false,done:Boolean=false):Boolean=db.withTransaction {
        if(!valid(b))return@withTransaction false
        for(record in page) {
            validate(b,record)
            val old=dao.remote(b.uid,b.generation,record.key)
            if(old!=null && old.version>=record.version)continue
            val pending=dao.forKey(b.uid,b.generation,record.key)
            // An initial local copy identical in follow intent is a merge, not a new re-follow.
            val baselineMatch=record.key.startsWith("subscription_") && pending.isNotEmpty() && pending.all { it.baseline } && !record.deleted && pending.all {
                SubscriptionSyncStore.decode(it.payload)["feedUrl"]==record.payload["feedUrl"]
            }
            if(baselineMatch)pending.forEach { dao.acknowledge(b.uid,b.generation,it.operationId) }
            remember(b,record)
            if(pending.isEmpty() || baselineMatch)projectSubscription(db,record)
        }
        val state=dao.binding()!!
        dao.putBinding(if(bootstrap) state.copy(bootstrapAfter=page.lastOrNull()?.key?:state.bootstrapAfter,
            bootstrapped=done,cursor=if(done)state.bootstrapHead else state.cursor)
            else state.copy(cursor=page.lastOrNull()?.sequence?:state.cursor))
        true
    }
    private fun validate(b:SyncBindingEntity,r:CloudSubscription) {
        check((r.key.startsWith("subscription_") || PortableRecord.isPortable(r.key) || CollectionRecord.supports(r.key) || r.key.startsWith("artifact_")) && r.version>0 && r.sequence>0)
        SyncProtocol.validate(SyncMutation("remote",SyncOwner(b.uid,b.generation),r.key,r.version-1,r.payload,r.deleted))
        if(!r.deleted && r.key.startsWith("subscription_")) { require(r.payload["podcastId"]?.matches(Regex("[A-Za-z0-9_-]{1,128}"))==true);require(r.payload["feedUrl"]!=null) }
    }
    private suspend fun remember(b:SyncBindingEntity,r:CloudSubscription) {
        validate(b,r)
        val old=dao.remote(b.uid,b.generation,r.key)
        val payload=JSONObject(r.payload.toSortedMap()).toString()
        if(old!=null && old.version>r.version)return
        if(old!=null && old.version==r.version)check(old.payload==payload && old.deleted==r.deleted && old.sequence==r.sequence) { "Cloud version identity changed." }
        dao.putRemote(SyncRemoteEntity(b.uid,b.generation,r.key,r.version,payload,r.deleted,r.sequence))
    }
}

internal suspend fun projectSubscription(db:AppDatabase,r:CloudSubscription) {
        if(r.key.startsWith("artifact_")){projectArtifact(db,r);return}
        if(CollectionRecord.supports(r.key)){projectCollection(db,r);return}
        if(PortableRecord.isPortable(r.key)){projectPortable(db,r);return}
        val p=r.payload
        val old=if(r.deleted) {
            // Tombstones deliberately contain no content; find local identity by the stable key.
            db.library().podcasts().firstOrNull { SubscriptionSyncStore.key(it)==r.key }
        } else db.library().podcastByUrl(p.getValue("feedUrl")) ?: db.library().podcast(p.getValue("podcastId"))
        if(r.deleted) { old?.let { db.library().putPodcast(it.copy(subscribed=false)) };return }
        val value=(old?:PodcastEntity(p.getValue("podcastId"),p.getValue("feedUrl"),p["title"]?:"Podcast",originalFeedUrl=p["originalFeedUrl"]?:p.getValue("feedUrl")))
            .copy(title=p["title"]?:old?.title?:"Podcast",author=p["author"]?:"",subscribed=true)
        db.library().putPodcast(value)
        db.sync().approve(SyncPublicFeedEntity(value.id))
    }
