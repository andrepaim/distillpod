package dev.distillpod.android.sync

import dev.distillpod.android.data.*
import dev.distillpod.core.sync.*
import androidx.room.withTransaction

class CollectionSync(private val db:AppDatabase,private val isolatedDemo:Boolean=false,private val changed:()->Unit={}) {
    private val portable=PortableLibrarySync(db,isolatedDemo)
    suspend fun queue(ids:List<String>) {
        db.withTransaction {
            val unique=ids.distinct()
            require(unique.all {db.library().episode(it)!=null})
            db.library().clearQueue();db.library().insertQueue(unique.mapIndexed {i,id->QueueEntryEntity(id,i)})
            if(SubscriptionCloudAccess.instrumentation && !isolatedDemo)return@withTransaction
            if(db.sync().view("__extended_scope")==null)return@withTransaction
            val public=unique.mapNotNull {id->db.library().episode(id)?.let {ep->portable.identity(ep)?.let {id to it}}}
            db.sync().putView(SyncViewEntity("__queue_excluded",sequence=(unique.size-public.size).toLong()))
            // Large queues remain local until the bounded representation can be approved.
            if(public.size>200){db.sync().putView(SyncViewEntity("__queue_excluded",sequence=unique.size.toLong()));return@withTransaction}
            public.forEach {(id,identity)->enqueueIfChanged("episode_$id",identity)}
            val view=db.sync().view("queue_library")?:SyncViewEntity("queue_library")
            db.sync().putView(view.copy(active=true))
            enqueueIfChanged("queue_library",mapOf("items" to public.joinToString(","){it.first}))
        };changed()
    }
    private suspend fun enqueueIfChanged(key:String,payload:Map<String,String>) {
        val b=db.sync().binding()?:return
        val pending=db.sync().forKey(b.uid,b.generation,key)
        val last=pending.lastOrNull()
        if(last!=null && SubscriptionSyncStore.decode(last.payload)==payload)return
        val remote=db.sync().remote(b.uid,b.generation,key)
        if(pending.isEmpty() && remote!=null && !remote.deleted && SubscriptionSyncStore.decode(remote.payload)==payload)return
        portable.enqueue(key,payload)
    }
    suspend fun preference(name:String,value:String) {
        val mutation=SyncMutation("check",SyncOwner("check","check"),"preference_$name",0,mapOf("value" to value))
        SyncProtocol.validate(mutation)
        db.withTransaction {
            db.sync().putPreference(SyncPreferenceEntity(name,value))
            if(db.sync().view("__extended_scope")!=null)enqueueIfChanged(mutation.key,mutation.payload)
        };changed()
    }
}

internal suspend fun projectCollection(db:AppDatabase,r:CloudSubscription,force:Boolean=false) {
    when {
        r.key.startsWith("preference_")->db.sync().putPreference(SyncPreferenceEntity(r.key.removePrefix("preference_"),r.payload.getValue("value")))
        r.key.startsWith("episode_")-> {
            restoreEpisodeIdentity(db,r.payload)
        }
        else-> {
            val ids=CollectionRecord.items(r.payload.getValue("items"))
            check(ids.all {db.library().episode(it)!=null}) {"Queue episode metadata is missing; sync will retry."}
            val localIds=db.library().queue().map {it.episodeId}
            val view=db.sync().view(r.key)
            if(!force && view?.active==true) {
                val visiblePublic=localIds.filter {id->db.library().episode(id)?.let {PortableLibrarySync(db,isolatedDemo=true).identity(it)}!=null}
                if(visiblePublic!=ids)CollectionSync(db,isolatedDemo=true).queue(localIds)
                else db.sync().putView(view.copy(version=r.version))
                return
            }
            val localPrivate=db.library().queue().map {it.episodeId}.filter {id->db.library().episode(id)?.let {PortableLibrarySync(db,isolatedDemo=true).identity(it)}==null}
            db.library().clearQueue();db.library().insertQueue((ids+localPrivate).distinct().mapIndexed {i,id->QueueEntryEntity(id,i)})
        }
    }
    val view=db.sync().view(r.key)?:SyncViewEntity(r.key)
    db.sync().putView(view.copy(version=r.version))
}
