package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xml.sax.SAXException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** #146: a failed refresh records why, so the Library can name the problem instead of a generic notice. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeedRefreshErrorTest {
    @Test fun `reasons distinguish connection, server status and unreadable feeds`() {
        val offline = "Couldn't reach the feed. Check your connection and try again."
        assertEquals(offline, feedRefreshError(UnknownHostException("feeds.example.test")))
        assertEquals(offline, feedRefreshError(ConnectException("refused")))
        assertEquals(offline, feedRefreshError(SocketTimeoutException("timeout")))
        assertEquals("The feed address no longer works (HTTP 404).", feedRefreshError(FeedHttpException(404)))
        assertEquals("The feed address no longer works (HTTP 410).", feedRefreshError(FeedHttpException(410)))
        assertEquals("The feed refused access (HTTP 403).", feedRefreshError(FeedHttpException(403)))
        assertEquals("The feed server had a problem (HTTP 503). Try again later.", feedRefreshError(FeedHttpException(503)))
        assertEquals("The feed server answered HTTP 429.", feedRefreshError(FeedHttpException(429)))
        assertEquals("The feed couldn't be read. It may not be a valid podcast feed.", feedRefreshError(SAXException("bad xml")))
        // Raw exception text can contain URLs or provider details, so unknown failures stay generic.
        assertEquals("Refresh failed. Saved episodes are still available.", feedRefreshError(IllegalStateException("https://private.example.test/token")))
    }

    @Test fun `refresh stores the reason and a later success clears it`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            MockWebServer().use { server ->
                db.library().putPodcast(PodcastEntity("p", server.url("/rss").toString(), "Show"))
                val repo = PodcastRepository(db, OkHttpClient())
                server.enqueue(MockResponse().setResponseCode(404))
                assertTrue(runCatching { repo.refresh("p") }.isFailure)
                assertEquals("The feed address no longer works (HTTP 404).", db.library().podcast("p")!!.error)
                server.enqueue(MockResponse().setBody("""<rss version="2.0"><channel><title>Show</title><item><guid>a</guid><title>A</title>
                    <enclosure url="https://example.test/a.mp3" type="audio/mpeg" length="1"/></item></channel></rss>"""))
                repo.refresh("p")
                assertNull(db.library().podcast("p")!!.error)
            }
        } finally { db.close() }
    }
}
