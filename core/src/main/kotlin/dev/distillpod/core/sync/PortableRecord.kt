package dev.distillpod.core.sync

/** Small, self-contained metadata snapshots. No job state, local paths or SDK credentials. */
object PortableRecord {
    val identityFields=setOf("episodeId","podcastId","feedUrl","originalFeedUrl","podcastTitle","podcastAuthor","guid","title","audioUrl","durationMs","fingerprint")
    val progressFields=identityFields+setOf("positionMs","sessionId","deviceId","deviceSequence")
    val completionFields=identityFields+setOf("completed","origin","sessionId")
    val momentFields=identityFields+setOf("momentId","startMs","endMs","excerpt","quote","insight","revision","createdAt","model")
    val contextualMomentFields = momentFields + setOf("momentSchema", "sourceJson")
    fun isPortable(key:String)=listOf("progress_","completion_","moment_").any {key.startsWith(it)}
    fun validate(m:SyncMutation) {
        if(!isPortable(m.key))return
        if(m.deleted){require(m.key.startsWith("moment_") && m.payload.isEmpty());return}
        val p=m.payload
        val fields=when {m.key.startsWith("progress_")->progressFields;m.key.startsWith("completion_")->completionFields;else->momentFields}
        require(p.keys==fields || m.key.startsWith("moment_") && p.keys==contextualMomentFields) {"Incomplete portable snapshot"}
        for(id in listOf("episodeId","podcastId"))require(p.getValue(id).matches(Regex("[A-Za-z0-9_-]{1,100}")))
        require(p.getValue("guid").isNotBlank() && p.getValue("guid").toByteArray().size<=2048)
        for(text in listOf("podcastTitle","podcastAuthor","title","fingerprint"))require(p.getValue(text).toByteArray().size<=1000)
        fun number(field:String):Long {val raw=p.getValue(field);require(raw.matches(Regex("0|[1-9][0-9]{0,15}")));return raw.toLong().also {require(it<9_000_000_000_000_000)}}
        number("durationMs")
        when {
            m.key.startsWith("progress_")-> {
                require(m.key=="progress_"+p.getValue("episodeId"));number("positionMs");number("deviceSequence")
                for(id in listOf("sessionId","deviceId"))require(p.getValue(id).matches(Regex("[A-Za-z0-9_-]{1,100}")))
            }
            m.key.startsWith("completion_")-> {
                require(m.key=="completion_"+p.getValue("episodeId"));require(p["completed"] in setOf("true","false"))
                require(p["origin"] in setOf("explicit","automatic"));require(p.getValue("sessionId").matches(Regex("[A-Za-z0-9_-]{1,100}")))
            }
            else-> {
                require(p.getValue("momentId").matches(Regex("[A-Za-z0-9_-]{1,100}")) && m.key=="moment_"+p.getValue("momentId"))
                require(number("endMs")>=number("startMs"));number("createdAt")
                if ("momentSchema" in p) {
                    require(p.getValue("momentSchema")=="2" && p.getValue("sourceJson").toByteArray().size<=32768)
                }
                require(listOf("excerpt","quote","insight").sumOf {p.getValue(it).toByteArray().size}<=96*1024)
                for(field in listOf("revision","model"))require(p.getValue(field).toByteArray().size<=1000)
            }
        }
    }
}
