package dev.distillpod.core.sync
object ArtifactRecord {
    fun validate(m:SyncMutation) {
        if(!m.key.startsWith("artifact_"))return
        require(!m.deleted && m.payload.keys==PortableRecord.identityFields+setOf("hash","expandedBytes","schema"))
        val p=m.payload
        require(m.key=="artifact_"+p.getValue("episodeId") && p["schema"]=="1" && p.getValue("hash").matches(Regex("[a-f0-9]{64}")))
        val size=p.getValue("expandedBytes");require(size.matches(Regex("[1-9][0-9]{0,7}")) && size.toLong()<=ContentArtifacts.MAX_EXPANDED)
        CollectionRecord.validate(m.copy(key="episode_"+p.getValue("episodeId"),payload=p.filterKeys {it in PortableRecord.identityFields}))
    }
}
