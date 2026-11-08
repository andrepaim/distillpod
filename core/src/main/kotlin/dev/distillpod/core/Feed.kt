package dev.distillpod.core

import java.io.StringReader
import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.w3c.dom.Element
import org.w3c.dom.Document

/** Feed identity is independent of redirect/enclosure URLs. Optional metadata preserves older callers. */
data class FeedEpisode(val id: String, val guid: String, val title: String, val audioUrl: String,
    val durationMs: Long?, val transcriptUrl: String?, val publishedAt: String?, val description: String = "",
    val imageUrl: String? = null, val mimeType: String? = null, val transcriptType: String? = null, val webpageUrl: String? = null, val sourceOrder: Int? = null)
data class Feed(val title: String, val episodes: List<FeedEpisode>, val author: String = "", val description: String = "", val imageUrl: String? = null, val websiteUrl: String? = null)

fun plainDescription(text: String): String = text.replace(Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1>"), "")
    .replace(Regex("<[^>]*>"), " ").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
    .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace(Regex("\\s+"), " ").trim()

fun parseFeed(feedUrl: String, xml: String, baseUrl: String = feedUrl): Feed {
    require(xml.length <= 10_000_000) { "Feed too large" }
    require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) { "XML declarations for entities/DTDs are prohibited" }
    val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
    val builder = factory.newDocumentBuilder()
    builder.setEntityResolver { _, _ -> throw org.xml.sax.SAXException("External entities are prohibited") }
    val document = builder.parse(InputSource(StringReader(xml)))
    return feedFromDocument(feedUrl, document, baseUrl)
}

internal fun feedFromDocument(feedUrl: String, document: Document, baseUrl: String): Feed {
    fun Element.children(name: String): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
        .filter { (it.localName ?: it.tagName.substringAfter(':')) == name }
    fun Element.text(name: String): String? = children(name).firstOrNull()?.textContent?.trim()?.takeIf(String::isNotBlank)
    fun url(raw: String?): String? = raw?.trim()?.takeIf { it.isNotBlank() }?.let {
        runCatching { URI(baseUrl).resolve(it).takeIf { u -> u.scheme?.lowercase() in setOf("https", "http") && u.host != null && u.userInfo == null }?.toASCIIString() }.getOrNull()
    }
    fun Element.webpage(): String? = if (namespaceURI == "http://www.w3.org/2005/Atom") {
        children("link").firstNotNullOfOrNull { link ->
            if (link.getAttribute("rel") in listOf("", "alternate") && link.getAttribute("type") in listOf("", "text/html", "application/xhtml+xml")) url(link.getAttribute("href")) else null
        }
    } else url(text("link"))
    fun Element.image(): String? = children("image").firstNotNullOfOrNull { url(it.getAttribute("href").ifBlank { it.text("url") ?: "" }) }
        ?: children("thumbnail").firstNotNullOfOrNull { url(it.getAttribute("url")) }
    fun duration(raw: String?): Long? = raw?.trim()?.let {
        runCatching {
            val parts = it.split(':'); require(parts.size in 1..3)
            val seconds = parts.fold(0.0) { total, p -> total * 60 + p.toDouble() }
            require(seconds.isFinite() && seconds >= 0 && seconds <= 7 * 86400)
            (seconds * 1000).toLong()
        }.getOrNull()
    }
    fun date(raw: String?): String? = raw?.let { value ->
        runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString() }
            .getOrElse { runCatching { Instant.parse(value).toString() }.getOrNull() }
    }
    val root = document.documentElement
    val atom = (root.localName ?: root.tagName) == "feed"
    val channel = if (atom) root else root.children("channel").firstOrNull() ?: error("Missing RSS channel")
    val showImage = channel.image() ?: url(channel.text("logo") ?: channel.text("icon"))
    val episodes = channel.children(if (atom) "entry" else "item").mapNotNull { item ->
        val enclosure = item.children(if (atom) "link" else "enclosure").firstOrNull { !atom || it.getAttribute("rel") == "enclosure" }
        val audio = url(enclosure?.getAttribute(if (atom) "href" else "url")) ?: return@mapNotNull null
        val guid = item.text(if (atom) "id" else "guid") ?: audio
        val timed = item.children("transcript").sortedBy { if (it.getAttribute("type") in setOf("text/vtt", "application/srt", "application/x-subrip")) 0 else 1 }.firstOrNull()
        FeedEpisode(episodeId(feedUrl, guid), guid, plainDescription(item.text("title") ?: "Untitled"), audio,
            duration(item.text("duration")), url(timed?.getAttribute("url")),
            date(item.text(if (atom) "published" else "pubDate") ?: item.text("updated")),
            plainDescription(item.text("description") ?: item.text("summary") ?: item.text("encoded") ?: item.text("content") ?: ""),
            item.image() ?: showImage, enclosure?.getAttribute("type")?.takeIf(String::isNotBlank), timed?.getAttribute("type")?.takeIf(String::isNotBlank), item.webpage())
    }.distinctBy { it.id }
    val author = channel.children("author").firstOrNull()?.let { it.text("name") ?: it.textContent.trim() } ?: channel.text("managingEditor") ?: ""
    return Feed(plainDescription(channel.text("title") ?: "Untitled"), episodes, plainDescription(author),
        plainDescription(channel.text("description") ?: channel.text("subtitle") ?: ""), showImage, channel.webpage())
}
