package dev.distillpod.core.sync

/** Queue order is separate from device playback state; preferences are independent revisions. */
object CollectionRecord {
    val preferences=setOf("model","language","aiModel")
    fun supports(key:String)=key=="queue_library" || key.startsWith("preference_") || key.startsWith("episode_")
    fun validate(m:SyncMutation) {
        if(!supports(m.key))return
        require(!m.deleted)
        when {
            m.key=="queue_library"-> {
                require(m.payload.keys==setOf("items"))
                val ids=items(m.payload.getValue("items"))
                require(ids.size<=200 && ids.distinct().size==ids.size && ids.all {it.matches(Regex("[A-Za-z0-9_-]{1,100}"))})
            }
            m.key.startsWith("preference_")-> {
                require(m.payload.keys==setOf("value"))
                val name=m.key.removePrefix("preference_");val value=m.payload.getValue("value")
                require(name in preferences)
                require(when(name) {"model"->value in setOf("tiny","base");"language"->value in setOf("auto","en","pt");else->value.length<=200 && value.none {it.isISOControl()}})
            }
            else-> {
                require(m.payload.keys==PortableRecord.identityFields && m.key=="episode_"+m.payload.getValue("episodeId"))
                // Reuse the same strict identity contract as significant progress.
                PortableRecord.validate(m.copy(key="progress_"+m.payload.getValue("episodeId"),payload=m.payload+mapOf("positionMs" to "0","sessionId" to "identity","deviceId" to "identity","deviceSequence" to "0")))
            }
        }
    }
    fun items(raw:String)=if(raw.isEmpty())emptyList() else raw.split(',')
}
