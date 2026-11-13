package dev.distillpod.android.sync

import androidx.room.withTransaction
import dev.distillpod.android.data.*
import dev.distillpod.core.sync.*
import dev.distillpod.core.sync.ContentArtifact
import dev.distillpod.core.sync.ContentArtifacts
import org.json.JSONArray
import org.json.JSONObject

/** Completed portable content only; computation attempts and local partial checkpoints stay private. */
class FinishedContent(private val db:AppDatabase,private val isolatedDemo:Boolean=false) {
    suspend fun restore(artifact:ContentArtifact,replaceRevision:Boolean=false):Boolean {
        val value=JSONObject(ContentArtifacts.decode(artifact).toString(Charsets.UTF_8))
        require(value.getString("format")=="distillpod-finished-content" && value.getInt("schema")==1)
        require(value.keys().asSequence().toSet()==setOf("format","schema","identity","transcript","segments","coverage","analysis","messages","research"))
        val identityJson=value.getJSONObject("identity")
        val identity=identityJson.keys().asSequence().associateWith {identityJson.getString(it)}
        val id=identity.getValue("episodeId")
        SyncProtocol.validate(SyncMutation("check",SyncOwner("check","check"),"episode_$id",0,identity))
        val t=value.getJSONObject("transcript")
        val revision=t.getString("revision");val fingerprint=t.getString("fingerprint")
        require(revision.isNotBlank() && revision.length<=1000 && fingerprint==identity.getValue("fingerprint"))
        val duration=t.getLong("durationMs");require(duration>=0)
        val transcript=TranscriptEntity(id,revision,fingerprint,t.getString("model"),t.getString("language"),t.getString("source"),state="ready",durationMs=duration,processedMs=duration)
        fun rows(name:String):List<JSONObject> {
            val array=value.getJSONArray(name);require(array.length()<=100000)
            return (0 until array.length()).map {array.getJSONObject(it)}
        }
        val segments=rows("segments").map {r->
            val start=r.getLong("startMs");val end=r.getLong("endMs");val ordinal=r.getInt("ordinal")
            require(start>=0 && end>=start && end<=duration && ordinal>=0)
            SegmentEntity(id,revision,start,end,r.getString("text"),ordinal)
        }
        require(segments.distinctBy {it.startMs to it.ordinal}.size==segments.size)
        val coverage=rows("coverage").map {r->
            val start=r.getLong("startMs");val end=r.getLong("endMs");require(start>=0 && end>start && end<=duration)
            CoverageEntity(id,revision,start,end)
        }
        val analysis=if(value.isNull("analysis"))null else value.getJSONObject("analysis").let {a->
            require(a.getString("revision")==revision)
            JSONArray(a.getString("chaptersJson"));JSONArray(a.getString("adsJson"))
            AnalysisEntity(id,revision,state="succeeded",summary=a.getString("summary"),chaptersJson=a.getString("chaptersJson"),adsJson=a.getString("adsJson"),
                model=if(a.isNull("model"))null else a.getString("model"),promptVersion=a.getInt("promptVersion"))
        }
        val messages=rows("messages").map {m->
            require(m.getString("role") in setOf("user","assistant"));JSONArray(m.getString("sourcesJson"))
            MessageEntity(m.getString("id"),id,m.getString("role"),m.getString("text"),createdAt=m.getLong("createdAt"),replyTo=if(m.isNull("replyTo"))null else m.getString("replyTo"),
                sourcesJson=m.getString("sourcesJson"),revision=revision,fingerprint=fingerprint,model=if(m.isNull("model"))null else m.getString("model"))
        }
        require(messages.map {it.id}.distinct().size==messages.size && messages.all {it.id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && it.createdAt>=0})
        val reports=rows("research").map {r->
            JSONArray(r.getString("sourcesJson"))
            ResearchEntity(r.getString("id"),r.getString("distillationId"),state="succeeded",report=r.getString("report"),sourcesJson=r.getString("sourcesJson"),mode=r.getString("mode"),createdAt=r.getLong("createdAt"))
        }
        return db.withTransaction {
            val dao=db.library();val old=dao.episode(id);val oldTranscript=dao.transcript(id)
            if(old!=null && old.mediaFingerprint!=fingerprint)return@withTransaction false
            if(oldTranscript!=null && ((oldTranscript.revision!=revision && !replaceRevision) || oldTranscript.state in setOf("processing","queued")))return@withTransaction false
            val oldAnalysis=dao.analysis(id)
            if(oldAnalysis?.state in setOf("queued","running"))return@withTransaction false
            if(analysis!=null && oldAnalysis?.state=="succeeded" && !replaceRevision &&
                (oldAnalysis.revision!=analysis.revision || oldAnalysis.summary!=analysis.summary || oldAnalysis.chaptersJson!=analysis.chaptersJson || oldAnalysis.adsJson!=analysis.adsJson))return@withTransaction false
            // References can arrive in a later metadata page; retain the staged artifact and retry.
            if(reports.any {dao.distillation(it.distillationId)?.episodeId!=id})return@withTransaction false
            if(messages.any {it.replyTo!=null && messages.none {parent->parent.id==it.replyTo} && dao.message(it.replyTo)==null})return@withTransaction false
            val ep=restoreEpisodeIdentity(db,identity)
            check(ep.id==id) {"Episode aliases require explicit mapping before artifact import"}
            if(oldTranscript!=null && oldTranscript.revision==revision && oldTranscript.state=="ready") {
                require(dao.segments(id,revision)==segments) {"An immutable transcript revision changed"}
            } else {
                dao.deleteSegments(id);dao.deleteCoverage(id);dao.putTranscriptAt(transcript, TranscriptWriteSite.FINISHED_CONTENT);dao.putSegments(segments);coverage.forEach {dao.putCoverage(it)}
            }
            if(analysis!=null && (dao.analysis(id)==null || replaceRevision))dao.putAnalysis(analysis)
            for(message in messages) {
                val existing=dao.message(message.id)
                require(existing==null || (existing.episodeId==id && existing.text==message.text && existing.replyTo==message.replyTo)) {"Message identity changed"}
                if(existing==null)dao.putMessage(message)
            }
            for(report in reports)if(dao.research(report.id)==null)dao.putResearch(report)
            true
        }
    }
    suspend fun snapshot(episodeId:String):ContentArtifact?=db.withTransaction {
        document(episodeId,summarizeSegments=false)?.let {ContentArtifacts.encode(it.toString().toByteArray(Charsets.UTF_8))}
    }
    /**
     * A cheap change key for [snapshot], null exactly when no snapshot exists. It covers the same document
     * except the segment list, which is summarized by revision and row aggregates: a ready revision's rows
     * are immutable (see [restore]), so new text arrives as a new revision or as added/replaced rows.
     */
    suspend fun sourceKey(episodeId:String):String?=db.withTransaction {
        document(episodeId,summarizeSegments=true)?.let {
            java.security.MessageDigest.getInstance("SHA-256").digest("$SOURCE_KEY_VERSION\n$it".toByteArray(Charsets.UTF_8)).joinToString("") {b->"%02x".format(b)}
        }
    }
    private suspend fun document(episodeId:String,summarizeSegments:Boolean):JSONObject? {
        val dao=db.library();val ep=dao.episode(episodeId)?:return null
        val identity=PortableLibrarySync(db,isolatedDemo).identity(ep)?:return null
        val transcript=dao.transcript(episodeId)?.takeIf {it.state=="ready" && it.fingerprint==ep.mediaFingerprint}?:return null
        val segments:Any=if(summarizeSegments) {
            db.sync().segmentSummary(ep.id,transcript.revision).let {s->JSONObject().put("rows",s.rowCount).put("startMs",s.startMs).put("endMs",s.endMs)
                .put("ordinals",s.ordinals).put("textLength",s.textLength).put("rowIds",s.rowIds)}
        } else {
            val rows=dao.segments(ep.id,transcript.revision)
            require(rows.size<=100000)
            JSONArray(rows.map {s->JSONObject().put("startMs",s.startMs).put("endMs",s.endMs).put("ordinal",s.ordinal).put("text",s.text)})
        }
        val result=JSONObject().put("format","distillpod-finished-content").put("schema",1)
            .put("identity",JSONObject(identity)).put("transcript",JSONObject().put("revision",transcript.revision).put("fingerprint",transcript.fingerprint)
                .put("model",transcript.model).put("language",transcript.language).put("source",transcript.source).put("durationMs",transcript.durationMs))
            .put("segments",segments)
            .put("coverage",JSONArray(dao.coverage(ep.id,transcript.revision).map {c->JSONObject().put("startMs",c.startMs).put("endMs",c.endMs)}))
        val analysis=dao.analysis(ep.id)?.takeIf {it.state=="succeeded" && it.revision==transcript.revision}
        result.put("analysis",analysis?.let {a->JSONObject().put("revision",a.revision).put("summary",a.summary).put("chaptersJson",a.chaptersJson).put("adsJson",a.adsJson)
            .put("model",a.model?:JSONObject.NULL).put("promptVersion",a.promptVersion)}?:JSONObject.NULL)
        val messages=dao.messages().filter {it.episodeId==ep.id && it.state=="succeeded" && it.revision==transcript.revision && it.fingerprint==transcript.fingerprint}
        val messageIds=messages.map {it.id}.toSet()
        result.put("messages",JSONArray(messages.filter {it.replyTo==null || it.replyTo in messageIds}.map {m->JSONObject().put("id",m.id).put("role",m.role).put("text",m.text)
            .put("createdAt",m.createdAt).put("replyTo",m.replyTo?:JSONObject.NULL).put("sourcesJson",m.sourcesJson).put("model",m.model?:JSONObject.NULL)}))
        val moments=dao.distillations().filter {it.episodeId==ep.id}.map {it.id}.toSet()
        result.put("research",JSONArray(dao.researches().filter {it.distillationId in moments && it.state=="succeeded"}.map {r->JSONObject().put("id",r.id)
            .put("distillationId",r.distillationId).put("report",r.report).put("sourcesJson",r.sourcesJson).put("mode",r.mode).put("createdAt",r.createdAt)}))
        return result
    }
    private companion object {
        /** Change it with the segment serialization or summary above, so earlier keys stop matching. */
        const val SOURCE_KEY_VERSION="finished-content-source-1"
    }
}
