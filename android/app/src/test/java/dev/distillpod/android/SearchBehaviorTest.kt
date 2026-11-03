package dev.distillpod.android

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import dev.distillpod.android.features.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchBehaviorTest {
    private val candidate = PodcastCandidate("New result", "Creator", "https://example.com/feed", null)
    @Test fun `canceling search cancels its underlying network call`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val server = MockWebServer()
        val canceled = java.util.concurrent.atomic.AtomicInteger()
        val http = OkHttpClient.Builder().eventListener(object : okhttp3.EventListener() {
            override fun canceled(call: okhttp3.Call) { canceled.incrementAndGet() }
        }).build()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val repository = PodcastRepository(db, http, server.url("/search"))
            val job = launch(Dispatchers.IO) { repository.search("slow search") }
            assertNotNull(server.takeRequest(10, java.util.concurrent.TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertEquals(1, canceled.get())
        } finally { server.shutdown(); db.close() }
    }
    @Test fun `late obsolete search cannot replace a newer result`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val old = CompletableDeferred<List<PodcastCandidate>>()
        try {
            val search = PodcastSearch(scope, { if (it == "old") withContext(NonCancellable) { old.await() } else listOf(candidate) })
            search.submit("old"); assertTrue(search.state.value.loading)
            search.submit("new"); assertEquals("new", search.state.value.submittedQuery)
            old.complete(listOf(candidate.copy(title = "Obsolete"))); yield()
            assertEquals(listOf(candidate), search.state.value.results)
            assertEquals("new", search.state.value.submittedQuery)
        } finally { old.complete(emptyList()); scope.cancel() }
    }
    @Test fun `editing clears pending search and its eventual error`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        try {
            val search = PodcastSearch(scope, { withContext(NonCancellable) { release.await() }; error("Late failure") })
            search.submit("before"); search.clear(); release.complete(Unit); yield()
            assertEquals(SearchState(), search.state.value)
        } finally { release.complete(Unit); scope.cancel() }
    }
    @Test fun `empty results are a submitted search and retry clears failure`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var fail = true
        try {
            val search = PodcastSearch(scope, { if (fail) error("offline") else emptyList() })
            search.submit("science"); assertNotNull(search.state.value.error)
            fail = false; search.submit("science")
            assertNull(search.state.value.error); assertFalse(search.state.value.loading)
            assertEquals("science", search.state.value.submittedQuery); assertTrue(search.state.value.results.isEmpty())
        } finally { scope.cancel() }
    }
    @Test fun `directory artwork falls back past empty and invalid fields`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("""{"results":[{"feedUrl":"https://example.com/a","collectionName":"A","artworkUrl600":"","artworkUrl100":"https://example.com/small.png","primaryGenreName":"Science"},{"feedUrl":"https://example.com/b","collectionName":"B","artworkUrl600":"invalid","artworkUrl512":"https://example.com/medium.png"},{"feedUrl":"https://example.com/a","collectionName":"Duplicate"}]}"""))
            val results = PodcastRepository(db, OkHttpClient(), server.url("/directory")).search("science")
            assertEquals(2, results.size); assertEquals("https://example.com/small.png", results[0].imageUrl)
            assertEquals("Science", results[0].category); assertEquals("https://example.com/medium.png", results[1].imageUrl)
        } finally { server.shutdown(); db.close() }
    }
    @Test fun `recent searches are bounded deduplicated local and clearable`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = java.nio.file.Files.createTempDirectory("search-history-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settings = AppSettings(context, PreferenceDataStoreFactory.create(scope = scope) { File(folder, "test.preferences_pb") })
        try {
            repeat(9) { settings.rememberSearch("Topic $it") }
            settings.rememberSearch("topic 7")
            settings.rememberSearch("HTTPS://example.com/private?token=not-saved")
            val history = settings.recentSearches.first()
            assertEquals(6, history.size); assertEquals("topic 7", history.first())
            assertEquals(1, history.count { it.equals("topic 7", true) }); assertFalse(history.any { it.startsWith("http", true) })
            settings.clearSearches(); assertTrue(settings.recentSearches.first().isEmpty())
        } finally { scope.cancel(); folder.deleteRecursively() }
    }
}
