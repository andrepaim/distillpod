package dev.distillpod.android.sync

import androidx.room.withTransaction
import dev.distillpod.android.data.AppDatabase
import dev.distillpod.core.sync.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Separate local content staging from immutable upload and versioned metadata publication. */
class ArtifactSync(private val db:AppDatabase,private val files:ContentFiles,private val objects:ContentObjects,private val isolatedDemo:Boolean=false) {
    suspend fun saved(owner:SyncOwner,cached:SyncArtifactEntity):ContentArtifact {
        val artifact=files.read(owner,cached.hash,cached.expandedBytes)?:objects.get(owner,cached.hash,cached.expandedBytes).also {files.save(owner,it)}
        return artifact
    }
    suspend fun probe(owner:SyncOwner) {objects.probe(owner)}
    /** Work done by one capture: [rebuilt] snapshots were serialized; [unchanged] reused their staged artifact. */
    data class Capture(val rebuilt:Int=0,val unchanged:Int=0)
    suspend fun capture()=withContext(Dispatchers.IO){captureOnIo()}
    private suspend fun captureOnIo():Capture {
        if(SubscriptionCloudAccess.instrumentation && !isolatedDemo)return Capture()
        if(db.sync().view("__extended_scope")==null)return Capture()
        val binding=db.sync().binding()?.takeIf {it.enabled}?:return Capture()
        val owner=SyncOwner(binding.uid,binding.generation)
        var rebuilt=0;var unchanged=0
        for(transcript in db.library().transcripts().filter {it.state=="ready"}) {
            val content=FinishedContent(db,isolatedDemo)
            val key="artifact_${transcript.episodeId}";val sourceKey="__artifact_source_${transcript.episodeId}"
            val source=content.sourceKey(transcript.episodeId)?:continue
            // "<source key> <hash> <expanded bytes>": the artifact last built from exactly these inputs.
            // LibraryDao.putSegments deletes this record whenever the episode's segment rows are written.
            val recorded=db.sync().view(sourceKey)?.sessionId?.split(' ')?.takeIf {it.size==3}
            if(recorded!=null && recorded[0]==source && db.sync().view(key)?.sessionId==recorded[1] &&
                recorded[2].toLongOrNull()?.let {files.read(owner,recorded[1],it)}!=null){unchanged++;continue}
            // The key is recomputed with the snapshot so the record never pairs older inputs with newer content.
            val (built,artifact)=db.withTransaction {content.sourceKey(transcript.episodeId) to content.snapshot(transcript.episodeId)}
            if(built==null || artifact==null)continue
            rebuilt++
            db.sync().putView((db.sync().view(sourceKey)?:SyncViewEntity(sourceKey)).copy(sessionId="$built ${artifact.hash} ${artifact.expandedBytes}"))
            val same=db.sync().view(key)?.sessionId==artifact.hash
            if(same && files.read(owner,artifact.hash,artifact.expandedBytes)!=null)continue
            files.save(owner,artifact)
            if(same && db.sync().artifact(owner.uid,owner.generation,artifact.hash)!=null)continue
            db.withTransaction {
                val current=db.sync().binding()
                if(current?.lease!=binding.lease)return@withTransaction
                val portable=PortableLibrarySync(db,isolatedDemo)
                val identityJson=JSONObject(ContentArtifacts.decode(artifact).toString(Charsets.UTF_8)).getJSONObject("identity")
                val identity=identityJson.keys().asSequence().associateWith {identityJson.getString(it)}
                db.sync().putArtifact(SyncArtifactEntity(owner.uid,owner.generation,artifact.hash,artifact.expandedBytes,"prepared",files.path(owner,artifact.hash),transcript.episodeId,transcript.revision))
                portable.enqueue(key,identity+mapOf("hash" to artifact.hash,"expandedBytes" to artifact.expandedBytes.toString(),"schema" to "1"))
                val view=db.sync().view(key)?:SyncViewEntity(key)
                db.sync().putView(view.copy(sessionId=artifact.hash))
            }
        }
        return Capture(rebuilt=rebuilt,unchanged=unchanged)
    }
    suspend fun upload(operation:SyncOutboxEntity) {
        val payload=SubscriptionSyncStore.decode(operation.payload)
        val owner=SyncOwner(operation.uid,operation.generation)
        val artifact=files.read(owner,payload.getValue("hash"),payload.getValue("expandedBytes").toLong())?:error("The saved content upload needs recovery")
        validateArtifactIdentity(artifact,payload)
        objects.put(owner,artifact)
        db.sync().artifact(owner.uid,owner.generation,artifact.hash)?.let {db.sync().putArtifact(it.copy(state="uploaded"))}
    }
    suspend fun fetch(owner:SyncOwner,record:CloudSubscription) {
        val hash=record.payload.getValue("hash");val expanded=record.payload.getValue("expandedBytes").toLong()
        val cached=files.read(owner,hash,expanded)
        val artifact=cached?:objects.get(owner,hash,expanded).also {files.save(owner,it)}
        validateArtifactIdentity(artifact,record.payload)
        val document=JSONObject(ContentArtifacts.decode(artifact).toString(Charsets.UTF_8))
        val prior=db.sync().artifact(owner.uid,owner.generation,hash)
        db.sync().putArtifact(prior?:SyncArtifactEntity(owner.uid,owner.generation,hash,artifact.expandedBytes,"downloaded",files.path(owner,hash),record.payload.getValue("episodeId"),document.getJSONObject("transcript").getString("revision")))
    }
}

internal suspend fun projectArtifact(db:AppDatabase,r:CloudSubscription,replaceRevision:Boolean=false) {
    val binding=db.sync().binding()?:error("Library account is unavailable")
    val hash=r.payload.getValue("hash")
    val cached=db.sync().artifact(binding.uid,binding.generation,hash)?:error("Finished content is still downloading")
    val file=File(cached.localFile)
    require(file.length() in 1..ContentArtifacts.MAX_COMPRESSED.toLong())
    val artifact=ContentArtifact(1,hash,r.payload.getValue("expandedBytes").toLong(),file.readBytes())
    validateArtifactIdentity(artifact,r.payload)
    val applied=FinishedContent(db).restore(artifact,replaceRevision)
    val document=JSONObject(ContentArtifacts.decode(artifact).toString(Charsets.UTF_8))
    val reports=document.getJSONArray("research")
    val waiting=(0 until reports.length()).any {db.library().distillation(reports.getJSONObject(it).getString("distillationId"))==null} ||
        db.library().transcript(cached.episodeId)?.state in setOf("queued","processing")
    db.sync().putArtifact(cached.copy(state=if(applied)"applied" else if(waiting)"waiting" else "alternate"))
    val view=db.sync().view(r.key)?:SyncViewEntity(r.key)
    db.sync().putView(view.copy(version=r.version,sessionId=if(applied)hash else view.sessionId))
}

internal fun validateArtifactIdentity(artifact:ContentArtifact,payload:Map<String,String>) {
    val identity=JSONObject(ContentArtifacts.decode(artifact).toString(Charsets.UTF_8)).getJSONObject("identity")
    val actual=identity.keys().asSequence().associateWith {identity.getString(it)}
    require(actual==payload.filterKeys {it in PortableRecord.identityFields}) {"Artifact source identity changed"}
}

internal suspend fun applyStagedContent(db:AppDatabase,binding:SyncBindingEntity) {
    for(cached in db.sync().artifacts(binding.uid,binding.generation).filter {it.state in setOf("prepared","downloaded","waiting")}) {
        val key="artifact_${cached.episodeId}"
        val remote=db.sync().remote(binding.uid,binding.generation,key)
        val pending=db.sync().forKey(binding.uid,binding.generation,key)
        val currentHash=remote?.let {SubscriptionSyncStore.decode(it.payload)["hash"]}
        val pendingHashes=pending.mapNotNull {SubscriptionSyncStore.decode(it.payload)["hash"]}
        if(cached.hash!=currentHash && cached.hash !in pendingHashes){db.sync().putArtifact(cached.copy(state="retained"));continue}
        if(cached.state in setOf("waiting","downloaded") && pending.isEmpty() && remote!=null && cached.hash==currentHash) {
            projectArtifact(db,CloudSubscription(remote.entityKey,remote.version,SubscriptionSyncStore.decode(remote.payload),remote.deleted,remote.sequence))
        }
    }
}
