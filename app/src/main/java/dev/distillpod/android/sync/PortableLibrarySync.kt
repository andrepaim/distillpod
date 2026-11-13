package dev.distillpod.android.sync

import androidx.room.withTransaction
import dev.distillpod.android.data.*
import dev.distillpod.core.sync.*
import org.json.JSONObject
import java.util.UUID

/** Saves portable user actions and their durable cloud intent in the same Room transaction. */
class PortableLibrarySync(private val db:AppDatabase,private val isolatedDemo:Boolean=false,private val changed:()->Unit={}) {
    private val dao get()=db.library()
    private val sync get()=db.sync()
    private fun allowed()=!SubscriptionCloudAccess.instrumentation || isolatedDemo
    suspend fun checkpoint(id:String,position:Long,duration:Long,complete:Boolean,at:Long,sessionId:String,elapsedMs:Long,force:Boolean=false,active:Boolean=true) {
        var queued=false
        db.withTransaction {
            val ep=dao.episode(id) ?: return@withTransaction
            val bounded=position.coerceAtLeast(0).coerceAtMost(duration.takeIf {it>0}?:Long.MAX_VALUE)
            // Completion travels separately, so an old near-end snapshot cannot undo mark-unplayed.
            dao.progress(id,bounded,duration,false,at)
            if(!allowed()){if(complete)dao.markCompleted(id,true);return@withTransaction}
            val key="progress_$id"
            val prior=sync.view(key) ?: SyncViewEntity(key)
            val view=if(prior.sessionId==sessionId)prior else prior.copy(sessionId=sessionId,sequence=0,checkpointAt=0,active=active,
                completionBase=sync.view("completion_$id")?.version?:0)
            sync.putView(view.copy(active=active))
            val moved=ep.positionMs!=bounded
            if((moved || force || complete) && (view.sequence==0L || force || complete || elapsedMs-view.checkpointAt>=30_000)) {
                val device=sync.view("__device") ?: SyncViewEntity("__device",sessionId=UUID.randomUUID().toString()).also {sync.putView(it)}
                val payload=identity(ep.copy(durationMs=duration.takeIf {it>0}?:ep.durationMs))?.plus(mapOf("positionMs" to bounded.toString(),"sessionId" to sessionId,
                    "deviceId" to device.sessionId,"deviceSequence" to (view.sequence+1).toString()))
                if(payload!=null)queued=enqueue(key,payload,base=view.version) || queued
                sync.putView(view.copy(active=active,sequence=view.sequence+1,checkpointAt=elapsedMs))
            }
            if(complete && !ep.completed && sync.view("__completed_$id")?.sessionId!=sessionId) {
                sync.putView(SyncViewEntity("__completed_$id",sessionId=sessionId))
                val current=sync.remote(sync.binding()?.uid?:"",sync.binding()?.generation?:"","completion_$id")
                val explicitUnplayed=current?.let {SubscriptionSyncStore.decode(it.payload)}?.let {it["origin"]=="explicit" && it["completed"]=="false"}==true
                if(explicitUnplayed && (current?.version?:0)>view.completionBase)return@withTransaction
                dao.markCompleted(id,true)
                val payload=identity(ep)?.plus(mapOf("completed" to "true","origin" to "automatic","sessionId" to sessionId))
                if(payload!=null)queued=enqueue("completion_$id",payload,base=view.completionBase) || queued
            }
        }
        if(queued)changed()
    }
    suspend fun putMoment(value:DistillationEntity) {
        db.withTransaction {dao.putDistillation(value);recordMoment(value)}
        changed()
    }
    /** Called inside a transaction which has already checked the AI attempt/source identity. */
    internal suspend fun recordMoment(value:DistillationEntity):Boolean {
        val ep=dao.episode(value.episodeId) ?: return false
        var payload=identity(ep)?.plus(mapOf("momentId" to value.id,"startMs" to value.startMs.toString(),"endMs" to value.endMs.toString(),
            "excerpt" to value.excerpt,"quote" to (value.quote?:""),"insight" to (value.insight?:""),"revision" to value.revision,
            "fingerprint" to value.fingerprint,"createdAt" to value.createdAt.toString(),"model" to (value.model?:""))) ?: return false
        if(value.sourceJson.isNotBlank() || sync.view("__contextual_moments")!=null) {
            MomentGeneration.validateSource(value.sourceJson,value.startMs,value.endMs)
            payload=payload+mapOf("momentSchema" to "2","sourceJson" to value.sourceJson)
        }
        return enqueue("moment_${value.id}",payload)
    }
    suspend fun deleteMoment(id:String) {
        db.withTransaction {
            val value=dao.distillation(id) ?: return@withTransaction
            val ep=dao.episode(value.episodeId)
            dao.deleteDistillation(id)
            if(ep!=null && identity(ep)!=null)enqueue("moment_$id",emptyMap(),deleted=true)
        };changed()
    }
    suspend fun markCompleted(id:String,complete:Boolean) {
        db.withTransaction {
            val ep=dao.episode(id) ?: return@withTransaction
            dao.markCompleted(id,complete)
            identity(ep)?.let {enqueue("completion_$id",it+mapOf("completed" to complete.toString(),"origin" to "explicit","sessionId" to UUID.randomUUID().toString()))}
        };changed()
    }
    suspend fun finishPlayback(id:String,position:Long,duration:Long,at:Long,sessionId:String,elapsedMs:Long) {
        checkpoint(id,position,duration,false,at,sessionId,elapsedMs,force=true,active=false)
        db.sync().view("queue_library")?.let {db.sync().putView(it.copy(active=false))}
    }
    suspend fun releaseSessions() {sync.releasePlaybackViews()}
    internal suspend fun seed(approved:Set<String>) {
        seedRecords(dao.episodes().filter {it.podcastId in approved},dao.distillations().filter {dao.episode(it.episodeId)?.podcastId in approved})
    }
    /**
     * Queues records a backup restore inserted, inside the restore's transaction; call [uploadSoon]
     * after it commits. Feeds not shared with sync stay local, as for any other local change.
     */
    suspend fun seedRestored(episodeIds:Collection<String>,momentIds:Collection<String>):Boolean=
        seedRecords(episodeIds.mapNotNull {dao.episode(it)},momentIds.mapNotNull {dao.distillation(it)})
    fun uploadSoon()=changed()
    /**
     * Versions before query-bearing media could sync kept every record of such an episode on the phone.
     * Queue them once; a record the cloud or outbox already holds (sent by a newer phone) is left as is.
     */
    internal suspend fun seedQueryMedia():Boolean {
        val episodes=dao.episodes().filter {ep->MediaLocator.portable(ep.audioUrl,ep.guid).let {it!=null && it!=ep.audioUrl}}
        val ids=episodes.map {it.id}.toSet()
        return seedRecords(episodes,dao.distillations().filter {it.episodeId in ids},onlyNew=true)
    }
    private suspend fun seedRecords(episodes:List<EpisodeEntity>,moments:List<DistillationEntity>,onlyNew:Boolean=false):Boolean {
        val binding=sync.binding() ?: return false
        suspend fun wanted(key:String)=!onlyNew || (sync.remote(binding.uid,binding.generation,key)==null && sync.forKey(binding.uid,binding.generation,key).isEmpty())
        var queued=false
        for(ep in episodes) {
            val identity=identity(ep) ?: continue
            // A session playing this episode sends its own checkpoints; a seed beside it would only conflict.
            if((ep.positionMs>0 || ep.lastPlayedAt>0) && sync.view("progress_${ep.id}")?.active!=true && wanted("progress_${ep.id}"))
                queued=enqueue("progress_${ep.id}",identity+mapOf("positionMs" to ep.positionMs.toString(),"sessionId" to UUID.randomUUID().toString(),"deviceId" to "initial","deviceSequence" to "0")) || queued
            if(ep.completed && wanted("completion_${ep.id}"))
                queued=enqueue("completion_${ep.id}",identity+mapOf("completed" to "true","origin" to "explicit","sessionId" to UUID.randomUUID().toString())) || queued
        }
        for(moment in moments)if(wanted("moment_${moment.id}"))queued=recordMoment(moment) || queued
        return queued
    }
    internal suspend fun identity(ep:EpisodeEntity):Map<String,String>? {
        if(!allowed() || sync.approved(ep.podcastId)==null)return null
        val p=dao.podcast(ep.podcastId) ?: return null
        // Signed or tokenized enclosure queries never leave the phone; feed and GUID identify the episode.
        val audioUrl=MediaLocator.portable(ep.audioUrl,ep.guid) ?: return null
        val identity=mapOf("episodeId" to ep.id,"podcastId" to p.id,"feedUrl" to p.feedUrl,"originalFeedUrl" to p.originalFeedUrl.ifBlank {p.feedUrl},
            "podcastTitle" to p.title,"podcastAuthor" to p.author,"guid" to ep.guid,"title" to ep.title,"audioUrl" to audioUrl,"durationMs" to ep.durationMs.toString(),"fingerprint" to ep.mediaFingerprint)
        // Validate all locators before enqueue, even when the outgoing action is a tombstone.
        return identity.takeIf {runCatching {SyncProtocol.validate(SyncMutation("check",SyncOwner("check","check"),"identity",0,it))}.isSuccess}
    }
    internal suspend fun enqueue(key:String,payload:Map<String,String>,deleted:Boolean=false,base:Long?=null):Boolean {
        if(!allowed())return false
        val binding=sync.binding() ?: return false
        val pending=sync.forKey(binding.uid,binding.generation,key)
        val version=base ?: pending.firstOrNull()?.baseVersion ?: sync.view(key)?.version ?: 0
        val mutation=SyncMutation(UUID.randomUUID().toString(),SyncOwner(binding.uid,binding.generation),key,version,payload,deleted)
        if(runCatching {SyncProtocol.validate(mutation)}.isFailure)return false
        pending.filter {it.state!="uploading"}.forEach {sync.acknowledge(binding.uid,binding.generation,it.operationId)}
        sync.enqueue(SyncOutboxEntity(mutation.id,binding.uid,binding.generation,key,version,JSONObject(payload.toSortedMap()).toString(),deleted))
        sync.putBinding(binding.copy(status=if(binding.enabled)"pending" else "paused"));return true
    }
    suspend fun resolve(key:String,useLocal:Boolean) = db.withTransaction {
        check(allowed())
        val binding=sync.binding() ?: error("Connect your sync account first.")
        val pending=sync.forKey(binding.uid,binding.generation,key)
        check(pending.none {it.state=="uploading"} && pending.any {it.state=="conflict"}) {"Wait for sync, then review the conflict."}
        val remote=sync.remote(binding.uid,binding.generation,key) ?: error("Cloud candidate is unavailable")
        val latest=pending.last()
        pending.forEach {sync.acknowledge(binding.uid,binding.generation,it.operationId)}
        val old=sync.view(key) ?: SyncViewEntity(key)
        sync.putView(old.copy(version=remote.version,active=false))
        if(useLocal)enqueue(key,SubscriptionSyncStore.decode(latest.payload),latest.deleted,remote.version)
        else {
            val record=CloudSubscription(key,remote.version,SubscriptionSyncStore.decode(remote.payload),remote.deleted,remote.sequence)
            if(key.startsWith("artifact_"))projectArtifact(db,record,replaceRevision=true) else if(CollectionRecord.supports(key))projectCollection(db,record,force=true) else projectPortable(db,record,force=true)
        }
        sync.putBinding(binding.copy(status=if(binding.enabled)"pending" else "paused"))
    }
}

internal suspend fun projectPortable(db:AppDatabase,r:CloudSubscription,force:Boolean=false) {
    val dao=db.library();val p=r.payload;val oldView=db.sync().view(r.key) ?: SyncViewEntity(r.key)
    if(r.deleted) {dao.deleteDistillation(r.key.removePrefix("moment_"));db.sync().putView(oldView.copy(version=r.version));return}
    val ep=restoreEpisodeIdentity(db,p)
    when {
        r.key.startsWith("progress_")-> {
            if(oldView.active && !force) {
                // Our own ack advances this session; an unrelated session remains a candidate.
                if(oldView.sessionId==p["sessionId"])db.sync().putView(oldView.copy(version=r.version))
                else {
                    // An intentional cloud pull into an active demo/real library keeps a durable
                    // local candidate. Ordinary instrumented local writes never create active views.
                    PortableLibrarySync(db,isolatedDemo=true).checkpoint(ep.id,ep.positionMs,ep.durationMs,false,ep.lastPlayedAt,
                        oldView.sessionId,oldView.checkpointAt,force=true)
                }
                return
            }
            dao.putEpisode(ep.copy(positionMs=p.getValue("positionMs").toLong()))
        }
        r.key.startsWith("completion_")->dao.markCompleted(ep.id,p.getValue("completed").toBooleanStrict())
        else-> {
            val insight=p.getValue("insight").ifBlank {null};val quote=p.getValue("quote").ifBlank {null}
            val source=p["sourceJson"].orEmpty()
            MomentGeneration.validateSource(source,p.getValue("startMs").toLong(),p.getValue("endMs").toLong())
            val restored=DistillationEntity(p.getValue("momentId"),ep.id,p.getValue("startMs").toLong(),p.getValue("endMs").toLong(),p.getValue("excerpt"),quote,insight,
                p.getValue("revision"),p.getValue("fingerprint"),state=if(insight!=null)"succeeded" else "saved",createdAt=p.getValue("createdAt").toLong(),model=p.getValue("model").ifBlank {null},sourceJson=source)
            val local=dao.distillation(restored.id)
            // An acknowledgement of our own snapshot must not erase a queued/reviewing attempt.
            if(local==null || momentSnapshot(local)!=momentSnapshot(restored) || force) {
                if(local!=null && local.insight!=null)dao.putMomentVersion(MomentVersionEntity(UUID.randomUUID().toString(),local.id,momentSnapshot(local)))
                dao.putDistillation(restored)
            }
        }
    }
    db.sync().putView(oldView.copy(version=r.version,active=false))
}

internal suspend fun restoreEpisodeIdentity(db:AppDatabase,p:Map<String,String>):EpisodeEntity {
    val dao=db.library()
    var podcast=dao.podcastByUrl(p.getValue("feedUrl")) ?: dao.podcastByUrl(p.getValue("originalFeedUrl")) ?: dao.podcast(p.getValue("podcastId"))
    if(podcast==null) {
        podcast=PodcastEntity(p.getValue("podcastId"),p.getValue("feedUrl"),p.getValue("podcastTitle"),author=p.getValue("podcastAuthor"),subscribed=false,originalFeedUrl=p.getValue("originalFeedUrl"))
        dao.putPodcast(podcast)
    }
    db.sync().approve(SyncPublicFeedEntity(podcast.id))
    val ep=dao.episodesForPodcast(podcast.id).firstOrNull {it.guid==p.getValue("guid")} ?: dao.episode(p.getValue("episodeId")) ?: EpisodeEntity(p.getValue("episodeId"),podcast.id,p.getValue("guid"),p.getValue("title"),p.getValue("audioUrl"),durationMs=p.getValue("durationMs").toLong(),mediaFingerprint=p.getValue("fingerprint"))
    if(dao.episode(ep.id)==null)dao.putEpisode(ep)
    return ep
}
