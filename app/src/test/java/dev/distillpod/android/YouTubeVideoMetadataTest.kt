package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import dev.distillpod.android.video.*
import dev.distillpod.core.YouTubeVideoMetadata
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant

/** Issue #125: subscribed YouTube videos keep their source publication date and original description. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YouTubeVideoMetadataTest {
    private val channel = "https://www.youtube.com/@fixturechannel/videos"
    private val channelId = "UCSHZKyawb77ixDdsGog4iWA"
    private val playlistId = "PLaE_mZALZ0V3lAEAvIfZC-aKf2ATIY3uy"
    private val playlist = "https://www.youtube.com/playlist?list=$playlistId"

    private fun atom(vararg entries: Triple<String, String, String>) = """<?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/" xmlns="http://www.w3.org/2005/Atom">
        <published>2006-09-20T05:17:16+00:00</published>
        ${entries.joinToString("") { (id, published, description) -> """<entry><yt:videoId>$id</yt:videoId>
            <published>$published</published><media:group><media:description>$description</media:description></media:group></entry>""" }}
        </feed>""".toByteArray()

    // Flat channel/playlist extraction: yt-dlp omits dates and descriptions for every entry.
    private val flat = """{"title":"Fixture","channel_id":"$channelId","entries":[
        {"id":"ABCDEFGHIJK","title":"Newest","timestamp":null,"description":null},
        {"id":"heoWstJTwu4","title":"Middle"},
        {"id":"abcdefghijk","title":"Archive"}]}"""

    @Test fun `channel refresh adds exact publication times and original descriptions without reordering`() = runBlocking {
        val requested = mutableListOf<String>()
        val source = YouTubeSource({ _, flat, _ -> assertTrue(flat); JSONObject(this@YouTubeVideoMetadataTest.flat) }, YouTubeMetadataFetcher { url ->
            requested += url
            // The public feed lists an older video first; channel order must still follow the source.
            atom(Triple("heoWstJTwu4", "2026-10-01T08:00:00+00:00", "Middle description"),
                Triple("ABCDEFGHIJK", "2026-11-10T23:52:20+00:00", "Line one\n\nLine two"))
        })
        val feed = source.feed("show", channel)
        assertEquals(listOf("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"), requested)
        assertEquals(listOf("ABCDEFGHIJK", "heoWstJTwu4", "abcdefghijk"), feed.episodes.map { it.guid })
        assertEquals(listOf(0, 1, 2), feed.episodes.map { it.sourceOrder })
        assertEquals("2026-11-10T23:52:20Z", feed.episodes[0].publishedAt)
        assertEquals("Line one\n\nLine two", feed.episodes[0].description)
        assertEquals("2026-10-01T08:00:00Z", feed.episodes[1].publishedAt)
        // Outside the feed window nothing is invented, and a JSON null never becomes the text "null".
        assertNull(feed.episodes[2].publishedAt)
        assertEquals("", feed.episodes[2].description)
    }

    @Test fun `playlist metadata never changes the playlist order and a failed feed keeps the refresh working`() = runBlocking {
        val requested = mutableListOf<String>()
        val dated = YouTubeSource({ _, _, _ -> JSONObject(flat) }, YouTubeMetadataFetcher { url ->
            requested += url
            atom(Triple("abcdefghijk", "2026-11-11T10:00:00+00:00", "Archive described"))
        }).feed("show", playlist)
        assertEquals(listOf("https://www.youtube.com/feeds/videos.xml?playlist_id=$playlistId"), requested)
        assertEquals(listOf("ABCDEFGHIJK", "heoWstJTwu4", "abcdefghijk"), dated.episodes.map { it.guid })
        assertEquals("Archive described", dated.episodes[2].description)

        val failing = YouTubeSource({ _, _, _ -> JSONObject(flat) }, YouTubeMetadataFetcher { throw IOException("feed unavailable") })
            .feed("show", channel)
        assertEquals(listOf("ABCDEFGHIJK", "heoWstJTwu4", "abcdefghijk"), failing.episodes.map { it.guid })
        assertTrue(failing.episodes.all { it.publishedAt == null && it.description == "" })
    }

    @Test fun `the public feed request is bounded and rejects unsuccessful responses`() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("<feed/>"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(404))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("x".repeat(2_000_001)))
            val fetcher = HttpYouTubeMetadataFetcher(OkHttpClient())
            assertEquals("<feed/>", String(fetcher.fetch(server.url("/feed").toString())))
            assertThrows(IOException::class.java) { fetcher.fetch(server.url("/missing").toString()) }
            assertThrows(IOException::class.java) { fetcher.fetch(server.url("/huge").toString()) }
        }
    }

    @Test fun `single video details prefer the exact timestamp and keep a date-only day stable across time zones`() {
        val exact = YouTubeSource { url, flat, _ ->
            assertEquals("https://www.youtube.com/watch?v=heoWstJTwu4", url); assertFalse(flat)
            JSONObject("""{"id":"heoWstJTwu4","upload_date":"20261110","timestamp":1794354740,"description":"Original\ntext"}""")
        }.details("https://www.youtube.com/watch?v=heoWstJTwu4")
        assertEquals(YouTubeVideoMetadata("2026-11-10T23:52:20Z", "Original\ntext"), exact)
        val dateOnly = YouTubeSource { _, _, _ -> JSONObject("""{"id":"heoWstJTwu4","upload_date":"20261110","description":null}""") }
            .details("https://youtu.be/heoWstJTwu4")
        assertEquals(YouTubeVideoMetadata("2026-11-10T12:00:00Z", ""), dateOnly)
    }

    @Test fun `refresh without source metadata keeps known dates descriptions and listening state`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        var feedAvailable = true
        val source = YouTubeSource({ _, _, _ -> JSONObject(flat) }, YouTubeMetadataFetcher {
            if (!feedAvailable) throw IOException("feed unavailable")
            atom(Triple("ABCDEFGHIJK", "2026-11-10T23:52:20+00:00", "Original description"))
        })
        try {
            val repo = PodcastRepository(db, OkHttpClient(), youtube = source)
            val id = repo.subscribe(channel)
            val newest = db.library().episodesForPodcast(id).first()
            assertEquals(Instant.parse("2026-11-10T23:52:20Z").toEpochMilli(), newest.publishedAt)
            assertEquals("Original description", newest.description)
            db.library().putEpisode(newest.copy(positionMs = 42_000))
            feedAvailable = false
            repo.refresh(id)
            val refreshed = db.library().episode(newest.id)!!
            assertEquals(newest.id, refreshed.id)
            assertEquals(Instant.parse("2026-11-10T23:52:20Z").toEpochMilli(), refreshed.publishedAt)
            assertEquals("Original description", refreshed.description)
            assertEquals(42_000L, refreshed.positionMs)
            assertEquals(listOf("ABCDEFGHIJK", "heoWstJTwu4", "abcdefghijk"), db.library().episodesForPodcast(id).map { it.guid })
        } finally { db.close() }
    }

    @Test fun `opening an older video fills only missing metadata and a failure can be retried`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        var detailsAvailable = false
        var detailCalls = 0
        val source = YouTubeSource({ url, flat, _ ->
            if (flat) JSONObject(this@YouTubeVideoMetadataTest.flat) else {
                detailCalls++
                if (!detailsAvailable) throw IOException("extraction unavailable")
                assertEquals("https://www.youtube.com/watch?v=abcdefghijk", url)
                JSONObject("""{"id":"abcdefghijk","timestamp":1700000000,"description":"From YouTube"}""")
            }
        }, YouTubeMetadataFetcher { throw IOException("feed unavailable") })
        try {
            val repo = PodcastRepository(db, OkHttpClient(), youtube = source)
            val id = repo.subscribe(channel)
            val archive = db.library().episodesForPodcast(id).single { it.guid == "abcdefghijk" }
            db.library().putEpisode(archive.copy(positionMs = 9_000, completed = true))

            repo.loadEpisodeDetails(archive.id)
            assertEquals(EpisodeDetailsState.FAILED, repo.episodeDetails.value[archive.id])
            assertEquals(0L, db.library().episode(archive.id)!!.publishedAt)

            detailsAvailable = true
            repo.loadEpisodeDetails(archive.id)
            assertEquals(EpisodeDetailsState.CHECKED, repo.episodeDetails.value[archive.id])
            val filled = db.library().episode(archive.id)!!
            assertEquals(1_700_000_000_000L, filled.publishedAt)
            assertEquals("From YouTube", filled.description)
            assertEquals(9_000L, filled.positionMs)
            assertTrue(filled.completed)

            // A checked video is not extracted again in this process.
            repo.loadEpisodeDetails(archive.id)
            assertEquals(2, detailCalls)

            // Known values are never replaced by on-demand details.
            val middle = db.library().episodesForPodcast(id).single { it.guid == "heoWstJTwu4" }
            db.library().putEpisode(middle.copy(publishedAt = 5_000L, description = "Feed text"))
            db.library().fillMissingEpisodeMetadata(middle.id, 9_999L, "Other text")
            assertEquals(5_000L, db.library().episode(middle.id)!!.publishedAt)
            assertEquals("Feed text", db.library().episode(middle.id)!!.description)
        } finally { db.close() }
    }
}
