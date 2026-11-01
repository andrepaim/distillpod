package dev.distillpod.core
import java.io.StringReader
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.w3c.dom.Element

data class FeedEpisode(val id: String, val guid: String, val title: String, val audioUrl: String, val durationMs: Long?, val transcriptUrl: String?, val publishedAt: String?)
data class Feed(val title: String, val episodes: List<FeedEpisode>)
fun parseFeed(feedUrl: String, xml: String): Feed {
    require(xml.length <= 10_000_000) { "Feed too large" }
    // Android's Harmony parser does not implement the Xerces feature flags.
    // Reject declarations before parsing rather than ignoring unsupported security flags.
    require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) { "XML declarations for entities/DTDs are prohibited" }
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
    }
    val builder = factory.newDocumentBuilder()
    builder.setEntityResolver { _, _ -> throw org.xml.sax.SAXException("External entities are prohibited") }
    val document = builder.parse(InputSource(StringReader(xml)))
    fun Element.text(name: String): String? = getElementsByTagName(name).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
    val channel = document.getElementsByTagName("channel").item(0) as? Element ?: error("Missing RSS channel")
    val nodes = channel.getElementsByTagName("item")
    val episodes = (0 until nodes.length).mapNotNull { i ->
        val item = nodes.item(i) as Element
        val enclosure = item.getElementsByTagName("enclosure").item(0) as? Element
        val audio = enclosure?.getAttribute("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return@mapNotNull null
        val guid = item.text("guid") ?: audio
        val duration = item.getElementsByTagNameNS("*", "duration").item(0)?.textContent?.trim()?.let { raw ->
            runCatching { raw.split(':').fold(0L) { total, part -> total * 60 + part.toLong() } * 1000 }.getOrNull()
        }
        val transcript = (item.getElementsByTagNameNS("*", "transcript").item(0) as? Element)?.getAttribute("url")
        val date = item.text("pubDate")?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString() }.getOrNull() }
        FeedEpisode(episodeId(feedUrl, guid), guid, item.text("title") ?: "Untitled", audio, duration, transcript, date)
    }.distinctBy { it.id }
    return Feed(channel.text("title") ?: "Untitled", episodes)
}
