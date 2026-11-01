package dev.distillpod.core
import org.junit.Assert.*
import org.junit.Test

class FeedTest {
    @Test fun `parses RSS enclosure duration transcript and UTC date`() {
        val rss = """<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:podcast="https://podcastindex.org/namespace/1.0"><channel><title>Show</title><item><guid>g</guid><title>Episode</title><enclosure url="https://example.com/e.mp3" type="audio/mpeg"/><itunes:duration>01:02:03</itunes:duration><podcast:transcript url="https://example.com/e.vtt" type="text/vtt"/><pubDate>Sun, 01 Nov 2026 12:00:00 GMT</pubDate></item></channel></rss>"""
        val feed = parseFeed("https://example.com/rss", rss)
        assertEquals("Show", feed.title)
        assertEquals(3723000L, feed.episodes.single().durationMs)
        assertEquals("https://example.com/e.vtt", feed.episodes.single().transcriptUrl)
        assertEquals("2026-11-01T12:00:00Z", feed.episodes.single().publishedAt)
    }
    @Test fun `guid falls back to enclosure and duplicate entries collapse`() {
        val item = """<item><title>E</title><enclosure url="https://example.com/e.mp3"/></item>"""
        assertEquals(1, parseFeed("feed", "<rss><channel><title>S</title>$item$item</channel></rss>").episodes.size)
    }
    @Test fun `rejects external entities`() {
        val xml = """<!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><rss><channel><title>&xxe;</title></channel></rss>"""
        assertThrows(Exception::class.java) { parseFeed("feed", xml) }
    }
    @Test fun `parses timed subtitles without inventing word alignment`() {
        val segments = parseVtt("WEBVTT\n\n00:00:01.000 --> 00:00:03.500\nHello world\n\n00:01:00.000 --> 00:01:02.000\nAnother thought\n")
        assertEquals(2, segments.size)
        assertEquals(3500L, segments.first().range.endMs)
        assertEquals("Hello world", segments.first().text)
    }
}
