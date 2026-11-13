package dev.distillpod.core

import java.io.FilterInputStream
import java.io.InputStream
import java.time.OffsetDateTime
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2

/** Source metadata for one video: an exact publication instant when known and its original description. */
data class YouTubeVideoMetadata(val publishedAt: String?, val description: String)

/**
 * YouTube's public, account-free Atom feed for a channel or playlist. It lists only the newest entries,
 * but unlike flat channel/playlist extraction it carries exact publication times and original descriptions.
 */
object YouTubeMetadata {
    private const val ATOM = "http://www.w3.org/2005/Atom"
    private const val MEDIA = "http://search.yahoo.com/mrss/"
    private const val YT = "http://www.youtube.com/xml/schemas/2015"
    private val video = Regex("[A-Za-z0-9_-]{11}")
    private val channel = Regex("UC[A-Za-z0-9_-]{22}")
    private val playlist = Regex("[A-Za-z0-9_-]{10,100}")

    fun channelFeed(id: String): String? = id.takeIf(channel::matches)?.let { "https://www.youtube.com/feeds/videos.xml?channel_id=$it" }
    fun playlistFeed(id: String): String? = id.takeIf(playlist::matches)?.let { "https://www.youtube.com/feeds/videos.xml?playlist_id=$it" }

    /** Parse at most [maximumEntries] entries from a bounded response; DTDs and external entities are rejected. */
    fun parse(input: InputStream, maximumEntries: Int = 50): Map<String, YouTubeVideoMetadata> {
        require(maximumEntries in 1..200)
        val result = linkedMapOf<String, YouTubeVideoMetadata>()
        val path = mutableListOf<Pair<String, String>>()
        val text = StringBuilder()
        var id: String? = null
        var published: String? = null
        var description = ""
        var entries = 0
        var nodes = 0
        class EnoughEntries : SAXException()
        val handler = object : DefaultHandler2() {
            override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw SAXException("Feed DTDs are prohibited") }
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource { throw SAXException("External entities are prohibited") }
            override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
                require(path.size < 32 && ++nodes <= 20_000) { "YouTube feed structure exceeds the supported size" }
                path.add(uri to localName)
                text.setLength(0)
                if (inEntry() && path.size == 2) { id = null; published = null; description = "" }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                require(text.length + length <= 64_000) { "YouTube feed text exceeds the supported size" }
                text.appendRange(ch, start, start + length)
            }
            override fun endElement(uri: String, localName: String, qName: String) {
                val depth = path.size
                if (inEntry()) when {
                    depth == 3 && uri == YT && localName == "videoId" -> id = text.toString().trim().takeIf(video::matches)
                    depth == 3 && uri == ATOM && localName == "published" -> published = runCatching {
                        OffsetDateTime.parse(text.toString().trim()).toInstant().toString()
                    }.getOrNull()
                    depth == 4 && path[2] == (MEDIA to "group") && uri == MEDIA && localName == "description" ->
                        description = text.toString().trim()
                    depth == 2 -> {
                        id?.let { result.putIfAbsent(it, YouTubeVideoMetadata(published, description)) }
                        if (++entries >= maximumEntries) throw EnoughEntries()
                    }
                }
                path.removeAt(path.lastIndex)
                text.setLength(0)
            }
            private fun inEntry() = path.size >= 2 && path[0] == (ATOM to "feed") && path[1] == (ATOM to "entry")
        }
        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
        reader.contentHandler = handler
        reader.entityResolver = handler
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        val bounded = object : FilterInputStream(input) {
            private var bytes = 0L
            private fun count(n: Int): Int { if (n > 0) bytes += n; require(bytes <= 2_000_000) { "YouTube feed exceeds 2 MB" }; return n }
            override fun read(): Int { val byte = super.read(); if (byte >= 0) count(1); return byte }
            override fun read(b: ByteArray, off: Int, len: Int): Int = count(`in`.read(b, off, len))
        }
        try { reader.parse(InputSource(bounded)) } catch (_: EnoughEntries) { }
        return result
    }
}
