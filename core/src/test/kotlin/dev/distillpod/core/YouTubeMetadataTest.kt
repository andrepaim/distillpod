package dev.distillpod.core

import org.junit.Assert.*
import org.junit.Test

class YouTubeMetadataTest {
    private val feed = """<?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/" xmlns="http://www.w3.org/2005/Atom">
         <title>Channel</title>
         <published>2006-09-20T05:17:16+00:00</published>
         <entry>
          <id>yt:video:2hJkprFnzhY</id>
          <yt:videoId>2hJkprFnzhY</yt:videoId>
          <title>Newest</title>
          <published>2026-11-10T23:52:20+00:00</published>
          <updated>2026-11-12T10:52:06+00:00</updated>
          <media:group>
           <media:title>Newest</media:title>
           <media:description>First line &amp; more

Second paragraph with https://example.com/link</media:description>
          </media:group>
         </entry>
         <entry>
          <yt:videoId>ZAzWPE9CItg</yt:videoId>
          <published>2026-10-26T19:51:51-03:00</published>
          <media:group><media:description></media:description></media:group>
         </entry>
         <entry>
          <yt:videoId>abcdefghijk</yt:videoId>
          <published>not a date</published>
         </entry>
         <entry>
          <yt:videoId>bad</yt:videoId>
          <published>2026-10-01T00:00:00+00:00</published>
         </entry>
        </feed>""".trimIndent()

    @Test fun `public feed entries keep exact publication instants and original descriptions by video id`() {
        val parsed = YouTubeMetadata.parse(feed.byteInputStream())
        assertEquals(setOf("2hJkprFnzhY", "ZAzWPE9CItg", "abcdefghijk"), parsed.keys)
        val newest = parsed.getValue("2hJkprFnzhY")
        // The feed-level channel creation date is never attributed to a video.
        assertEquals("2026-11-10T23:52:20Z", newest.publishedAt)
        assertEquals("First line & more\n\nSecond paragraph with https://example.com/link", newest.description)
        assertEquals("2026-10-26T22:51:51Z", parsed.getValue("ZAzWPE9CItg").publishedAt)
        assertEquals("", parsed.getValue("ZAzWPE9CItg").description)
    }

    @Test fun `unavailable or malformed values stay missing instead of being invented`() {
        val undated = YouTubeMetadata.parse(feed.byteInputStream()).getValue("abcdefghijk")
        assertNull(undated.publishedAt)
        assertEquals("", undated.description)
    }

    @Test fun `document type declarations and oversized feeds are rejected`() {
        val dtd = """<?xml version="1.0"?><!DOCTYPE feed [<!ENTITY x "y">]><feed xmlns="http://www.w3.org/2005/Atom"></feed>"""
        assertThrows(Exception::class.java) { YouTubeMetadata.parse(dtd.byteInputStream()) }
        val huge = "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>" + "x".repeat(3_000_000) + "</title></feed>"
        assertThrows(Exception::class.java) { YouTubeMetadata.parse(huge.byteInputStream()) }
    }

    @Test fun `metadata feed addresses accept only public channel and playlist identities`() {
        assertEquals("https://www.youtube.com/feeds/videos.xml?channel_id=UCSHZKyawb77ixDdsGog4iWA",
            YouTubeMetadata.channelFeed("UCSHZKyawb77ixDdsGog4iWA"))
        assertEquals("https://www.youtube.com/feeds/videos.xml?playlist_id=PLrAXtmErZgOdP_8GztsuKi9nrraNbKKp4",
            YouTubeMetadata.playlistFeed("PLrAXtmErZgOdP_8GztsuKi9nrraNbKKp4"))
        listOf("", "UC123", "XXSHZKyawb77ixDdsGog4iWA", "UCSHZKyawb77ixDdsGog4iWA&x=1").forEach { assertNull(YouTubeMetadata.channelFeed(it)) }
        listOf("", "short", "PL bad id", "PLrAXtmErZgOdP&x=1").forEach { assertNull(YouTubeMetadata.playlistFeed(it)) }
    }
}
