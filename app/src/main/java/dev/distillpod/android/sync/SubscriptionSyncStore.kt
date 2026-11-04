package dev.distillpod.android.sync

import androidx.room.withTransaction
import dev.distillpod.android.data.AppDatabase
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.core.episodeId
import dev.distillpod.core.sync.*
import org.json.JSONObject
import java.util.UUID

/** Retains account ownership even when paused or signed out. Different accounts cannot adopt it. */
class SubscriptionSyncStore(private val db: AppDatabase, private val isolatedDemo:Boolean=false) {
    private fun allowed()=!SubscriptionCloudAccess.instrumentation || isolatedDemo
    val approvals=db.sync().observeApprovals()
    val binding = db.sync().observeBinding()
    suspend fun bind(owner: SyncOwner, approvedFeeds:Set<String> = emptySet()) = db.withTransaction {
        check(allowed()) { "Production sync binding is disabled during instrumentation." }
        val old=db.sync().binding()
        check(old==null || (old.uid==owner.uid && old.generation==owner.generation)) {
            "This library belongs to another sync account. Sign in with its original account."
        }
        db.sync().putBinding(old?.copy(enabled=true,lease=UUID.randomUUID().toString(),status="pending")
            ?: SyncBindingEntity(uid=owner.uid,generation=owner.generation,enabled=true,lease=UUID.randomUUID().toString()))
        if(old==null)db.sync().clearApprovals()
        val newlyApproved=approvedFeeds.filter { db.library().podcast(it)!=null && db.sync().approved(it)==null }
        newlyApproved.forEach { db.sync().approve(SyncPublicFeedEntity(it)) }
        if(old!=null)newlyApproved.forEach { id -> db.library().podcast(id)?.let { enqueue(it,false) } }
        if(old==null) db.library().podcasts().filter { it.subscribed }.forEach { enqueue(it,true) }
    }
    suspend fun pause() = db.withTransaction {
        if(!allowed())return@withTransaction
        db.sync().binding()?.let { db.sync().putBinding(it.copy(enabled=false,lease=UUID.randomUUID().toString(),status="paused")) }
    }
    suspend fun follow(podcast: PodcastEntity, publicSource:Boolean = false) = db.withTransaction {
        db.library().putPodcast(podcast)
        if(!allowed())return@withTransaction
        if(publicSource)db.sync().approve(SyncPublicFeedEntity(podcast.id))
        enqueue(podcast,false)
    }
    private suspend fun enqueue(podcast:PodcastEntity,baseline:Boolean) {
        val binding=db.sync().binding() ?: return
        if(db.sync().approved(podcast.id)==null)return
        val key=key(podcast)
        val remote=db.sync().remote(binding.uid,binding.generation,key)
        val payload=if(podcast.subscribed) payload(podcast) else emptyMap()
        val mutation=SyncMutation(UUID.randomUUID().toString(),SyncOwner(binding.uid,binding.generation),key,remote?.version ?: 0,payload,!podcast.subscribed)
        // Query-bearing and credential-bearing sources remain on this phone.
        if(runCatching { SyncProtocol.validate(mutation.copy(payload=payload(podcast),deleted=false)); SyncProtocol.validate(mutation) }.isFailure) return
        db.sync().removeQueued(binding.uid,binding.generation,key)
        db.sync().enqueue(SyncOutboxEntity(mutation.id,binding.uid,binding.generation,key,mutation.baseVersion,
            JSONObject(payload.toSortedMap()).toString(),mutation.deleted,baseline=baseline))
        db.sync().putBinding(binding.copy(status=if(binding.enabled) "pending" else "paused"))
    }
    suspend fun resolve(key:String,useLocal:Boolean) = db.withTransaction {
        check(allowed()) { "Production sync changes are disabled during instrumentation." }
        val binding=db.sync().binding() ?: error("Connect your sync account first.")
        val operations=db.sync().forKey(binding.uid,binding.generation,key)
        check(operations.none { it.state=="uploading" }) { "Wait for the current upload to finish." }
        check(operations.any { it.state=="conflict" }) { "This conflict was already resolved." }
        val remote=db.sync().remote(binding.uid,binding.generation,key) ?: error("Cloud state is missing; reconnect to recover.")
        val local=db.library().podcasts().firstOrNull { key(it)==key }
        operations.forEach { db.sync().acknowledge(binding.uid,binding.generation,it.operationId) }
        if(useLocal) {
            check(local!=null) { "Local subscription is unavailable." }
            enqueue(local,false)
        } else projectSubscription(db,CloudSubscription(key,remote.version,decode(remote.payload),remote.deleted,remote.sequence))
        db.sync().putBinding(binding.copy(status=if(binding.enabled)"pending" else "paused"))
    }
    suspend fun pendingCount(): Int = db.sync().count()
    suspend fun activeOwner(): SyncOwner? = db.sync().binding()?.takeIf { it.enabled }?.let { SyncOwner(it.uid,it.generation) }
    companion object {
        private fun boundedText(raw:String):String {
            val points=raw.codePoints().iterator();val result=StringBuilder();var bytes=0
            while(points.hasNext()) {
                val value=points.nextInt();val part=String(Character.toChars(value));val size=part.toByteArray(Charsets.UTF_8).size
                if(bytes+size>1000)break
                result.append(part);bytes+=size
            }
            return result.toString()
        }
        fun key(podcast:PodcastEntity)="subscription_"+episodeId("subscription",podcast.originalFeedUrl.ifBlank { podcast.feedUrl })
        fun payload(podcast:PodcastEntity)=mapOf("podcastId" to podcast.id,"feedUrl" to podcast.feedUrl,
            "originalFeedUrl" to podcast.originalFeedUrl.ifBlank { podcast.feedUrl },"title" to boundedText(podcast.title),"author" to boundedText(podcast.author),"subscribed" to podcast.subscribed.toString())
        fun decode(raw:String):Map<String,String> { val json=JSONObject(raw);return json.keys().asSequence().associateWith { json.getString(it) } }
    }
}
