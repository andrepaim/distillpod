package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.AppDatabase
import dev.distillpod.android.data.PodcastCandidate
import dev.distillpod.android.data.PodcastRepository
import dev.distillpod.core.episodeId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PodcastBrowseTest {
    @Test fun `browsing stores playable episodes without following approving or scheduling a refresh`() = fixture { db, server ->
        val url = server.url("/feed").toString()
        val calls = mutableListOf<String>()
        val repository = PodcastRepository(db, OkHttpClient(),
            onSubscriptionChanged = { calls += "subscription" }, onFeedNeeded = { calls += it })
        server.enqueue(feed())

        val id = repository.browse(url)
        assertFalse(db.library().podcast(id)!!.subscribed)
        assertEquals("https://example.test/audio.mp3", db.library().episodesForPodcast(id).single().audioUrl)
        assertNull(db.sync().approved(id))
        assertTrue(calls.isEmpty())
    }

    @Test fun `browsing an unfollowed show preserves the existing episodes and progress`() = fixture { db, server ->
        val url = server.url("/feed").toString()
        val repository = PodcastRepository(db, OkHttpClient())
        server.enqueue(feed())
        val id = repository.subscribe(url)
        repository.unsubscribe(id)
        val episode = db.library().episodesForPodcast(id).single()
        db.library().putEpisode(episode.copy(positionMs = 1234, lastPlayedAt = 10))

        assertEquals(id, repository.browse(url))
        assertFalse(db.library().podcast(id)!!.subscribed)
        assertEquals(1234L, db.library().episode(episode.id)!!.positionMs)
        assertEquals(1, server.requestCount)
    }

    @Test fun `a concurrent follow retains its identity and approval when browse finishes a redirect`() = fixture { db, server ->
        val original = server.url("/original").toString()
        val final = server.url("/final").toString()
        val redirected = CountDownLatch(1)
        val followed = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/original" -> MockResponse().setResponseCode(302).setHeader("Location", final)
                "/final" -> {
                    redirected.countDown()
                    if (followed.await(5, TimeUnit.SECONDS)) feed() else MockResponse().setResponseCode(500)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val calls = mutableListOf<String>()
        val repository = PodcastRepository(db, OkHttpClient(),
            onSubscriptionChanged = { calls += "subscription" }, onFeedNeeded = { calls += it })
        kotlinx.coroutines.coroutineScope {
            val browsed = async { repository.browse(original) }
            val id = try {
                assertTrue(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { redirected.await(5, TimeUnit.SECONDS) })
                repository.follow(PodcastCandidate("Directory title", "", original, null))
            } finally {
                followed.countDown()
            }
            assertEquals(id, browsed.await())
            assertTrue(db.library().podcast(id)!!.subscribed)
            assertEquals(listOf(id), db.library().podcasts().map { it.id })
            assertEquals(episodeId(id, "fixture"), db.library().episodesForPodcast(id).single().id)
            assertNotNull(db.sync().approved(id))
            assertEquals(listOf(id, "subscription"), calls)
        }
    }

    private fun fixture(block: suspend (AppDatabase, MockWebServer) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try { MockWebServer().use { block(db, it) } }
        finally { db.close() }
    }

    private fun feed() = MockResponse().setBody("""
        <rss version="2.0"><channel><title>Fixture</title><item><guid>fixture</guid><title>Episode</title>
        <enclosure url="https://example.test/audio.mp3" type="audio/mpeg" length="1"/>
        </item></channel></rss>
    """.trimIndent())
}
