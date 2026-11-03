package dev.distillpod.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class StreamingFeedTest {
    @Test fun `large archives stop after recent complete entries without reading the tail`() {
        val item = """<item><guid>one</guid><title>Episode</title><enclosure url="https://example.test/audio"/></item>"""
        val prefix = """<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><title>Show</title><itunes:author>Author</itunes:author>$item"""
        val source = object : ByteArrayInputStream((prefix + " ".repeat(200000)).toByteArray()) {
            var read = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1024)).also { if (it > 0) read += it }
        }
        val feed = parseFeedStream("identity", source, "https://example.test/feed", 1)
        assertEquals("Author", feed.author); assertEquals(1, feed.episodes.size)
        assertTrue(source.read < 20000)
    }
    @Test fun `stream parser preserves Atom namespaces and rejects DTDs before expansion`() {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Atom</title><entry><id>x</id><title>Audio</title><link rel="enclosure" href="audio.mp3"/></entry></feed>"""
        assertEquals("https://example.test/audio.mp3", parseFeedStream("id", xml.byteInputStream(), "https://example.test/feed").episodes.single().audioUrl)
        assertThrows(org.xml.sax.SAXException::class.java) { parseFeedStream("id", """<!DOCTYPE rss [<!ENTITY x SYSTEM "file:///secret">]><rss><channel><title>&x;</title></channel></rss>""".byteInputStream(), "https://example.test") }
    }
}
