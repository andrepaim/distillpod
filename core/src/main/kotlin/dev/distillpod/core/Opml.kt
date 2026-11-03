package dev.distillpod.core

import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

data class OpmlFeed(val title: String, val url: String)
fun parseOpml(xml: String): List<OpmlFeed> {
    require(xml.length <= 2_000_000) { "OPML exceeds 2 MB" }
    require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) { "External XML declarations are prohibited" }
    val factory = DocumentBuilderFactory.newInstance().apply { isExpandEntityReferences = false }
    val builder = factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> throw org.xml.sax.SAXException("External XML is prohibited") } }
    val doc = builder.parse(InputSource(StringReader(xml)))
    require(doc.documentElement.tagName == "opml") { "Select an OPML subscription file" }
    val entries = doc.getElementsByTagName("outline")
    require(entries.length <= 5000) { "OPML has too many entries" }
    return (0 until entries.length).mapNotNull { i ->
        val entry = entries.item(i) as Element
        val raw = entry.getAttribute("xmlUrl").trim()
        if (raw.isBlank()) return@mapNotNull null
        val uri = URI(raw)
        require(uri.scheme?.lowercase() in listOf("http", "https") && uri.host != null && uri.userInfo == null) { "Unsupported feed URL in OPML" }
        OpmlFeed(entry.getAttribute("title").ifBlank { entry.getAttribute("text") }.take(500), uri.toASCIIString())
    }.distinctBy { it.url }.also { require(it.size <= 1000) { "Import at most 1000 subscriptions at a time" } }
}
fun writeOpml(feeds: List<OpmlFeed>): String {
    fun escaped(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><opml version=\"2.0\"><head><title>DistillPod subscriptions</title></head><body>" +
        feeds.distinctBy { it.url }.joinToString("") { "<outline type=\"rss\" text=\"${escaped(it.title)}\" title=\"${escaped(it.title)}\" xmlUrl=\"${escaped(it.url)}\"/>" } + "</body></opml>"
}
