package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import dev.distillpod.android.video.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YouTubeSourceTest {
    @Test fun `one slow video does not serialize extraction of another video`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val source = YouTubeSource { url, _, _ ->
            calls.incrementAndGet()
            if (url.contains("heoWstJTwu4")) { entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
            JSONObject("""{"formats":[{"protocol":"https","url":"https://a.googlevideo.com/audio","vcodec":"none","acodec":"aac"}]}""")
        }
        try {
            val first = pool.submit<YouTubeStreams> { source.streams("https://youtu.be/heoWstJTwu4") }
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val other = pool.submit<YouTubeStreams> { source.streams("https://youtu.be/abcdefghijk") }
            assertNotNull(other.get(2, java.util.concurrent.TimeUnit.SECONDS))
            val same = pool.submit<YouTubeStreams> { source.streams("https://www.youtube.com/watch?v=heoWstJTwu4") }
            release.countDown()
            assertEquals(first.get(2, java.util.concurrent.TimeUnit.SECONDS), same.get(2, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(2, calls.get())
            source.invalidate("https://youtu.be/heoWstJTwu4"); source.streams("https://youtu.be/heoWstJTwu4")
            assertEquals(3, calls.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }
    private val playlist = "https://www.youtube.com/playlist?list=PLaE_mZALZ0V3lAEAvIfZC-aKf2ATIY3uy"
    private val video = "https://www.youtube.com/watch?v=heoWstJTwu4"
    private fun source() = YouTubeSource { url, _, _ ->
        if (url.contains("/results?")) JSONObject("""{"entries":[{"title":"FALHA DE COBERTURA","channel":"Podpah TV","url":"$playlist"}]}""")
        else JSONObject("""{"title":"FALHA DE COBERTURA","channel":"Podpah TV","entries":[
            {"id":"heoWstJTwu4","title":"Falha de Cobertura #302: Acreditar é Cansativo","duration":1852},
            {"id":"abcdefghijk","title":"Private video","availability":"private"}]}""")
    }
    @Test fun `Falha is discoverable without Apple and survives refresh with listening progress`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("""{"results":[]}"""))
            val repo = PodcastRepository(db, OkHttpClient(), server.url("/search"), youtube = source())
            val result = repo.search("falha de cobertura").single()
            assertEquals("Podpah TV", result.author)
            val id = repo.follow(result)
            val episode = db.library().episodesForPodcast(id).single()
            assertEquals(video, episode.audioUrl)
            assertEquals("video/youtube", episode.mimeType)
            db.library().putEpisode(episode.copy(positionMs = 42_000, completed = true))
            repo.refresh(id)
            val refreshed = db.library().episodesForPodcast(id).single()
            assertEquals(episode.id, refreshed.id)
            assertEquals(42_000L, refreshed.positionMs)
            assertTrue(refreshed.completed)
            assertEquals(playlist, db.library().podcast(id)!!.feedUrl)
        } finally { server.shutdown(); db.close() }
    }
    @Test fun `a failed YouTube search does not discard Apple results`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("""{"results":[{"collectionName":"Audio show","feedUrl":"https://example.com/rss"}]}"""))
            val repo = PodcastRepository(db, OkHttpClient(), server.url("/search"), youtube = YouTubeSource { _, _, _ -> throw IOException() })
            assertEquals("Audio show", repo.search("audio show").single().title)
        } finally { server.shutdown(); db.close() }
    }
    @Test fun `original Portuguese audio wins over a higher bitrate English dub and DRM is ignored`() {
        val result = selectStreams(JSONObject("""{"formats":[
            {"protocol":"https","url":"https://a.googlevideo.com/dub","vcodec":"none","acodec":"aac","ext":"m4a","language_preference":-10,"abr":256},
            {"protocol":"https","url":"https://a.googlevideo.com/original","vcodec":"none","acodec":"aac","ext":"m4a","language_preference":10,"abr":128},
            {"protocol":"https","url":"https://a.googlevideo.com/drm","vcodec":"none","acodec":"aac","ext":"m4a","language_preference":100,"has_drm":true},
            {"protocol":"https","url":"https://a.googlevideo.com/video","vcodec":"avc1.64001f","acodec":"none","height":720},
            {"protocol":"https","url":"https://evil.test/video","vcodec":"avc1.64001f","acodec":"none","height":720}]}"""))
        assertEquals("https://a.googlevideo.com/original", result.audio)
        assertEquals("https://a.googlevideo.com/video", result.video)
    }
    @Test fun `canceling search interrupts extraction`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val source = YouTubeSource { _, _, _ ->
            entered.complete(Unit)
            try { Thread.sleep(30_000); JSONObject() } finally { stopped.complete(Unit) }
        }
        val job = launch { source.search("Falha") }
        withTimeout(5_000) { entered.await() }
        job.cancelAndJoin()
        assertTrue(stopped.isCompleted)
    }

    @Test fun `Apple failure still returns YouTube results and two empty providers stay empty`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        MockWebServer().use { server ->
            try {
                server.enqueue(MockResponse().setResponseCode(503))
                val repo = PodcastRepository(db, OkHttpClient(), server.url("/search"), youtube = source())
                assertEquals(playlist, repo.search("Falha").single().feedUrl)
                server.enqueue(MockResponse().setBody("""{"results":[]}"""))
                val empty = PodcastRepository(db, OkHttpClient(), server.url("/search"),
                    youtube = YouTubeSource { _, _, _ -> JSONObject("""{"entries":[]}""") })
                assertTrue(empty.search("missing show").isEmpty())
            } finally { db.close() }
        }
    }

    @Test fun `a failed provider with no results reports failure instead of a false empty search`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        MockWebServer().use { server ->
            try {
                for (status in listOf(200, 503)) {
                    server.enqueue(MockResponse().setResponseCode(status).setBody("""{"results":[]}"""))
                    val repo = PodcastRepository(db, OkHttpClient(), server.url("/search"),
                        youtube = YouTubeSource { _, _, _ -> throw IOException("offline") })
                    assertTrue(runCatching { repo.search("Falha") }.isFailure)
                }
            } finally { db.close() }
        }
    }

    @Test fun `following a saved YouTube source offline keeps the subscription and progress`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val id = PodcastRepository(db, OkHttpClient(), youtube = source()).subscribe(playlist)
            val episode = db.library().episodesForPodcast(id).single()
            db.library().putEpisode(episode.copy(positionMs = 12_000))
            val repo = PodcastRepository(db, OkHttpClient(), youtube = YouTubeSource { _, _, _ -> throw IOException("offline") })
            repo.unsubscribe(id)
            assertEquals(id, repo.subscribe(playlist))
            assertTrue(db.library().podcast(id)!!.subscribed)
            assertEquals(12_000L, db.library().episode(episode.id)!!.positionMs)
        } finally { db.close() }
    }

    @Test fun `single videos keep canonical identity and playlist feeds exclude restricted and live entries`() = runBlocking {
        val single = YouTubeSource { url, flat, _ ->
            assertEquals(video, url)
            assertFalse(flat)
            JSONObject("""{"id":"heoWstJTwu4","title":"Episode","upload_date":"20261107","duration":42}""")
        }.feed("show", "https://youtu.be/heoWstJTwu4?si=tracking")
        assertEquals(video, single.episodes.single().audioUrl)
        assertEquals("2026-11-07T00:00:00Z", single.episodes.single().publishedAt)
        val filtered = YouTubeSource { _, flat, limit ->
            assertTrue(flat)
            assertEquals(200, limit)
            JSONObject("""{"entries":[{"id":"heoWstJTwu4","title":"Episode"},
                {"id":"abcdefghijk","live_status":"is_live"},
                {"id":"abcdefghijk","live_status":"is_upcoming"},
                {"id":"abcdefghijk","availability":"needs_auth"},
                {"id":"abcdefghijk","availability":"premium_only"}]}""")
        }.feed("show", playlist)
        assertEquals(single.episodes.single().id, filtered.episodes.single().id)
    }

    @Test fun `stream cache uses video identity and never exposes CDN URLs as episode identity`() {
        var calls = 0
        val source = YouTubeSource { url, flat, limit ->
            calls++
            assertEquals(video, url)
            assertFalse(flat)
            assertEquals(1, limit)
            JSONObject("""{"formats":[{"protocol":"https","url":"https://a.googlevideo.com/audio?expire=123", "vcodec":"none","acodec":"aac","ext":"m4a"}]}""")
        }
        assertEquals(source.streams(video), source.streams("https://youtu.be/heoWstJTwu4"))
        assertEquals(1, calls)
    }

    @Test fun `live DRM and untrusted streams cannot be selected`() {
        for (json in listOf(
            """{"is_live":true}""",
            """{"live_status":"is_upcoming"}""",
            """{"formats":[{"protocol":"https","url":"https://a.googlevideo.com/drm","vcodec":"none","acodec":"aac","has_drm":true}]}""",
            """{"formats":[{"protocol":"https","url":"https://googlevideo.com.evil.test/audio","vcodec":"none","acodec":"aac"}]}"""
        )) assertTrue(runCatching { selectStreams(JSONObject(json)) }.isFailure)
    }

    @Test fun `YouTube sources and progress stay local under the existing cloud protocol`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val store = dev.distillpod.android.sync.SubscriptionSyncStore(db)
            store.bind(dev.distillpod.core.sync.SyncOwner("fixture-owner", "fixture-generation"))
            val id = PodcastRepository(db, OkHttpClient(), youtube = source()).subscribe(playlist)
            val episode = db.library().episodesForPodcast(id).single()
            dev.distillpod.android.sync.PortableLibrarySync(db).checkpoint(episode.id, 5_000, 60_000, false,
                1_000, "fixture-session", 1_000, force = true)
            assertEquals(5_000L, db.library().episode(episode.id)!!.positionMs)
            assertNull(db.sync().approved(id))
            assertEquals(0, store.pendingCount())
        } finally { db.close() }
    }
}
