package dev.distillpod.core

import org.junit.Assert.*
import org.junit.Test

class SocialPostTest {
    @Test fun `quote normalization preserves wording and rejects paraphrases`() {
        assertTrue(sourceQuoteMatches("Small daily routines", "Small\n daily\t routines build skills."))
        assertFalse(sourceQuoteMatches("Daily habits", "Small daily routines build skills."))
        assertFalse(sourceQuoteMatches("Invented words", ""))
        assertTrue(sourceQuoteMatches("", ""))
    }
    @Test fun `public links reject credentials queries local addresses and non-web schemes`() {
        assertEquals("https://publisher.example/episode#notes", postSourceUrl("https://publisher.example/episode#notes"))
        for (bad in listOf("file:///private", "javascript:alert(1)", "https://user:pass@host.test/", "https://host.test/?token=secret", "http://localhost/a", "http://192.168.1.3/a", "http://[::1]/a", "garbage")) assertNull(bad, postSourceUrl(bad))
    }
    @Test fun `RSS and Atom webpage links resolve identically in both parsers`() {
        val rss = """<rss><channel><title>Show</title><link>/show</link><item><guid>one</guid><title>Episode</title><link>/ep</link><enclosure url="/audio.mp3" /></item></channel></rss>"""
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Show</title><link rel="self" href="/feed"/><link rel="alternate" type="text/html" href="/show"/><entry><id>one</id><title>Episode</title><link rel="alternate" href="/ep"/><link rel="enclosure" href="/audio.mp3"/></entry></feed>"""
        for (xml in listOf(rss, atom)) {
            val feed = parseFeed("https://host.test/feed", xml)
            assertEquals("https://host.test/show", feed.websiteUrl)
            assertEquals("https://host.test/ep", feed.episodes.single().webpageUrl)
            assertEquals(feed, parseFeedStream("https://host.test/feed", xml.byteInputStream(), "https://host.test/feed"))
        }
    }
}
