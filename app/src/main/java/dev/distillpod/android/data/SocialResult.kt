package dev.distillpod.android.data

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A completed revision is replaced atomically, never by partial streaming output. */
data class SocialArtifact(val text: String = "", val quote: String = "", val image: String = "",
    val title: String = "", val description: String = "", val sources: String = "[]", val brief: String = "") {
    fun json() = JSONObject().put("text", text).put("quote", quote).put("image", image).put("title", title)
        .put("description", description).put("sources", JSONArray(sources)).put("brief", brief)
    companion object {
        fun parse(j: JSONObject) = SocialArtifact(j.getString("text"), j.getString("quote"), j.getString("image"), j.getString("title"),
            j.getString("description"), j.getJSONArray("sources").toString(), j.getString("brief")).also {
            require(it.text.length <= 16000 && it.quote.length <= 1600 && it.title.length <= 300 && it.description.length <= 16000 && it.brief.length <= 40000)
            require(it.image.isEmpty() || it.image.matches(Regex("[a-f0-9]{64}")))
            dev.distillpod.android.ai.EvidenceSource.list(it.sources)
        }
    }
}

data class SocialResult(val source: PostContent, val format: String, val stage: String = "queued", val attempt: String = UUID.randomUUID().toString(),
    val artifact: SocialArtifact? = null, val previous: SocialArtifact? = null, val instruction: String = "", val error: String = "",
    val research: String = "", val researchSources: String = "[]") {
    fun json(): String = JSONObject().put("version", 2).put("source", JSONObject(source.json())).put("format", format)
        .put("stage", stage).put("attempt", attempt).put("artifact", artifact?.json() ?: JSONObject.NULL)
        .put("previous", previous?.json() ?: JSONObject.NULL).put("instruction", instruction).put("error", error)
        .put("research", research).put("researchSources", JSONArray(researchSources)).toString()
    fun shareText(): String {
        val a = artifact ?: error("The post is still being prepared")
        return if (format == "infographic") a.description else listOf(a.quote.takeIf { it.isNotBlank() }?.let { "“$it”" }.orEmpty(), a.text, "${source.show} · ${source.episode} · ${dev.distillpod.core.postTimestamp(source.startMs)}", source.sourceUrl).filter { it.isNotBlank() }.joinToString("\n\n")
    }
    companion object {
        fun parse(raw: String): SocialResult {
            require(raw.length <= 200000)
            val j = JSONObject(raw)
            if (j.getInt("version") == 1) {
                val source = PostContent.parse(raw)
                return SocialResult(source, "legacy", "ready", "", SocialArtifact(text = source.body.ifBlank { listOf(source.headline,source.takeaway,source.perspective).filter(String::isNotBlank).joinToString("\n\n") }, quote = source.quote))
            }
            require(j.getInt("version") == 2)
            return SocialResult(PostContent.parse(j.getJSONObject("source").toString()), j.getString("format"), j.getString("stage"), j.getString("attempt"),
                j.optJSONObject("artifact")?.let(SocialArtifact::parse), j.optJSONObject("previous")?.let(SocialArtifact::parse), j.getString("instruction"),
                j.getString("error"), j.getString("research"), j.getJSONArray("researchSources").toString()).also {
                require(it.format in listOf("discussion", "infographic", "legacy"))
                require(it.stage in listOf("queued", "preparing", "researching", "illustrating", "ready", "failed", "canceled"))
                require(it.attempt.length <= 100)
                require(it.instruction.length <= 2000 && it.error.length <= 400 && it.research.length <= 50000)
                dev.distillpod.android.ai.EvidenceSource.list(it.researchSources)
                require(it.stage != "ready" || it.artifact != null)
                for (a in listOfNotNull(it.artifact, it.previous)) {
                    require(dev.distillpod.core.sourceQuoteMatches(a.quote, it.source.excerpt))
                    require(it.format != "infographic" || a.image.isNotBlank())
                }
            }
        }
    }
}

class SocialResultRepository(private val db: AppDatabase, private val enqueue: (String, String) -> Unit) {
    private val dao = db.library()
    suspend fun create(moment: String, format: String): String {
        require(format in listOf("discussion", "infographic"))
        val row = db.withTransaction {
            val original = PostRepository(db).create(moment)
            original.copy(content = SocialResult(PostContent.parse(original.content), format).json()).also { dao.putPost(it) }
        }
        enqueue(row.id, SocialResult.parse(row.content).attempt)
        return row.id
    }
    suspend fun get(id: String): SocialResult? = dao.post(id)?.let { SocialResult.parse(it.content) }
    suspend fun change(id: String, instruction: String) {
        require(instruction.length <= 2000)
        db.withTransaction {
            val row = dao.post(id) ?: error("Result was deleted")
            val result = SocialResult.parse(row.content)
            require(result.stage !in listOf("queued", "preparing", "researching", "illustrating")) { "Wait for the current version or cancel it first" }
            dao.putPost(row.copy(content = result.copy(format = if (result.format == "legacy") "discussion" else result.format,
                stage = "queued", attempt = UUID.randomUUID().toString(), instruction = instruction, error = "").json(), revision = row.revision + 1, updatedAt = System.currentTimeMillis()))
        }
        enqueue(id, get(id)!!.attempt)
    }
    suspend fun update(id: String, attempt: String, transform: (SocialResult) -> SocialResult): Boolean = db.withTransaction {
        val row = dao.post(id) ?: return@withTransaction false
        val result = SocialResult.parse(row.content)
        if (result.attempt != attempt || result.stage in listOf("ready", "canceled", "failed")) return@withTransaction false
        val changed = transform(result)
        SocialResult.parse(changed.json())
        dao.putPost(row.copy(content = changed.json(), revision = row.revision + 1, updatedAt = System.currentTimeMillis())); true
    }
    suspend fun cancel(id: String) = db.withTransaction {
        val row = dao.post(id) ?: return@withTransaction
        val result = SocialResult.parse(row.content)
        dao.putPost(row.copy(content = result.copy(stage = "canceled", attempt = UUID.randomUUID().toString()).json(), revision = row.revision + 1, updatedAt = System.currentTimeMillis()))
    }
    suspend fun undo(id: String) = db.withTransaction {
        val row = dao.post(id) ?: return@withTransaction
        val result = SocialResult.parse(row.content)
        require(result.stage !in listOf("queued", "preparing", "researching", "illustrating"))
        require(result.previous != null) { "No previous version" }
        dao.putPost(row.copy(content = result.copy(artifact = result.previous, previous = null, stage = "ready", attempt = UUID.randomUUID().toString(), error = "").json(), revision = row.revision + 1, updatedAt = System.currentTimeMillis()))
    }
    suspend fun editText(id: String, text: String) = db.withTransaction {
        require(text.length in 1..16000)
        val row = dao.post(id) ?: error("Result was deleted")
        val result = SocialResult.parse(row.content)
        require(result.stage !in listOf("queued", "preparing", "researching", "illustrating"))
        require(result.format != "infographic" && result.artifact != null)
        dao.putPost(row.copy(content = result.copy(artifact = result.artifact.copy(text = text), previous = result.artifact, stage = "ready", attempt = UUID.randomUUID().toString()).json(), revision = row.revision + 1, updatedAt = System.currentTimeMillis()))
    }
}
