package dev.distillpod.core

import org.junit.Assert.*
import org.junit.Test

class OpmlTest {
    @Test fun `round trip escapes names and deduplicates nested outlines`() {
        val feeds = listOf(OpmlFeed("A & B \"voices\"", "https://example.test/feed?one=1&two=2"))
        assertEquals(feeds, parseOpml(writeOpml(feeds + feeds)))
        assertEquals(listOf(OpmlFeed("Nested", "https://example.test/rss")), parseOpml("""<opml><body><outline text="Folder"><outline title="Nested" xmlUrl="https://example.test/rss"/></outline></body></opml>"""))
    }
    @Test fun `external entities and file URLs are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { parseOpml("""<!DOCTYPE opml [<!ENTITY e SYSTEM "file:///secret">]><opml/>""") }
        assertThrows(IllegalArgumentException::class.java) { parseOpml("""<opml><body><outline xmlUrl="file:///secret"/></body></opml>""") }
    }
}
