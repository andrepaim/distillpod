package dev.distillpod.android.data

import androidx.room.*
import androidx.room.withTransaction

/** Local authored content survives deletion of its original moment. */
@Entity(tableName = "post_drafts", foreignKeys = [ForeignKey(entity = DistillationEntity::class,
    parentColumns = ["id"], childColumns = ["sourceMomentId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("sourceMomentId")])
data class PostDraftEntity(@PrimaryKey val id: String, val sourceMomentId: String?, val content: String,
    val revision: Int, val updatedAt: Long)

/** Immutable source fields travel with a draft even after the original moment is removed. */
data class PostContent(
    val show: String = "", val episode: String = "", val excerpt: String = "",
    val startMs: Long = 0, val endMs: Long = 0, val sourceFingerprint: String = "",
    val sourceUrl: String = "", val destination: String = "LinkedIn", val language: String = "en",
    val perspective: String = "", val headline: String = "", val takeaway: String = "",
    val body: String = "", val quote: String = "", val layout: String = "Idea",
    val theme: String = "Ink", val square: Boolean = false, val branding: Boolean = true,
    val imageDescription: String = "",
) {
    fun json(): String = org.json.JSONObject().apply {
        put("version", 1)
        put("show", show); put("episode", episode); put("excerpt", excerpt)
        put("startMs", startMs); put("endMs", endMs); put("sourceFingerprint", sourceFingerprint)
        put("sourceUrl", sourceUrl); put("destination", destination); put("language", language)
        put("perspective", perspective); put("headline", headline); put("takeaway", takeaway)
        put("body", body); put("quote", quote); put("layout", layout); put("theme", theme)
        put("square", square); put("branding", branding); put("imageDescription", imageDescription)
    }.toString()

    fun validated(): PostContent = apply {
        require(destination in listOf("LinkedIn", "Instagram") && language in listOf("en", "pt-BR")) { "Choose a supported format and language" }
        require(layout in listOf("Idea", "Quote") && theme in listOf("Ink", "Lavender", "Mint")) { "Choose a supported design" }
        require(headline.length <= 300 && takeaway.length <= 2500 && body.length <= 12000 && perspective.length <= 2000 && imageDescription.length <= 4000) { "Post text is too long" }
        require(excerpt.length <= 24000 && quote.length <= 1600 && show.length <= 2000 && episode.length <= 2000) { "Source text is too long" }
        require(startMs >= 0 && endMs >= startMs && sourceFingerprint.length <= 2000) { "Invalid source metadata" }
        require(dev.distillpod.core.sourceQuoteMatches(quote, excerpt)) { "Quote must match the original excerpt. Use edited text as a takeaway instead." }
        require(sourceUrl.isBlank() || dev.distillpod.core.postSourceUrl(sourceUrl) != null) { "Use a public HTTP(S) link without credentials or query parameters" }
    }

    fun attribution(): String = listOf(show, episode, "${dev.distillpod.core.postTimestamp(startMs)}–${dev.distillpod.core.postTimestamp(endMs)}").filter { it.isNotBlank() }.joinToString(" · ")
    fun shareText(): String = listOf(body.ifBlank { listOf(headline, takeaway, perspective).filter { it.isNotBlank() }.joinToString("\n\n") },
        quote.takeIf { it.isNotBlank() }?.let { "“$it”" }.orEmpty(), "Source: ${attribution()}", sourceUrl).filter { it.isNotBlank() }.joinToString("\n\n")
    fun altText(): String = imageDescription.ifBlank { listOf(headline, if (layout == "Quote") "“$quote”" else takeaway, attribution()).filter { it.isNotBlank() }.joinToString(". ") }

    companion object {
        fun parse(raw: String, validate: Boolean = true): PostContent {
            require(raw.length <= 80000) { "Post is too large" }
            val j = org.json.JSONObject(raw)
            require(j.getInt("version") == 1) { "Unsupported post version" }
            val content = PostContent(j.getString("show"), j.getString("episode"), j.getString("excerpt"), j.getLong("startMs"), j.getLong("endMs"),
                j.getString("sourceFingerprint"), j.getString("sourceUrl"), j.getString("destination"), j.getString("language"),
                j.getString("perspective"), j.getString("headline"), j.getString("takeaway"), j.getString("body"), j.getString("quote"),
                j.getString("layout"), j.getString("theme"), j.getBoolean("square"), j.getBoolean("branding"), j.getString("imageDescription"))
            return if (validate) content.validated() else content
        }
    }
}

class PostRepository(private val db: AppDatabase) {
    private val dao = db.library()
    suspend fun create(momentId: String, destination: String = "LinkedIn"): PostDraftEntity = db.withTransaction {
        val moment = dao.distillation(momentId) ?: error("Saved moment is unavailable")
        val episode = dao.episode(moment.episodeId) ?: error("Episode is unavailable")
        val show = dao.podcast(episode.podcastId) ?: error("Podcast is unavailable")
        val publicSource = db.sync().approved(show.id) != null
        val quote = moment.quote.orEmpty().takeIf { dev.distillpod.core.sourceQuoteMatches(it, moment.excerpt) }.orEmpty()
        val content = PostContent(show.title.take(2000), episode.title.take(2000), moment.excerpt.take(24000), moment.startMs, moment.endMs, moment.fingerprint,
            sourceUrl = if (publicSource) (dev.distillpod.core.postSourceUrl(episode.webpageUrl) ?: dev.distillpod.core.postSourceUrl(show.websiteUrl)).orEmpty() else "",
            destination = destination, headline = "", takeaway = moment.insight.orEmpty().take(2500), quote = quote,
            layout = if (quote.isNotBlank()) "Quote" else "Idea")
        PostDraftEntity(java.util.UUID.randomUUID().toString(), momentId, content.validated().json(), 0, System.currentTimeMillis()).also { dao.putPost(it) }
    }
    suspend fun save(id: String, expectedRevision: Int, content: PostContent): PostDraftEntity = db.withTransaction {
        val current = dao.post(id) ?: error("Draft was deleted")
        require(current.revision == expectedRevision) { "This draft changed. Reopen it before saving." }
        val old = PostContent.parse(current.content)
        require(content.copy(show = old.show, episode = old.episode, excerpt = old.excerpt, startMs = old.startMs, endMs = old.endMs, sourceFingerprint = old.sourceFingerprint) == content) { "Source snapshot cannot be edited" }
        current.copy(content = content.validated().json(), revision = current.revision + 1, updatedAt = System.currentTimeMillis()).also { dao.putPost(it) }
    }
    suspend fun duplicate(id: String): PostDraftEntity = db.withTransaction {
        val old = dao.post(id) ?: error("Draft was deleted")
        old.copy(id = java.util.UUID.randomUUID().toString(), revision = 0, updatedAt = System.currentTimeMillis()).also { dao.putPost(it) }
    }
    suspend fun delete(id: String) = dao.deletePost(id)
}
