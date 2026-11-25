package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.ai.*
import dev.distillpod.android.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExploreTest {
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    private suspend fun moment(db: AppDatabase) {
        db.library().putPodcast(PodcastEntity("p", "https://example.test/feed", "Show"))
        db.library().putEpisode(EpisodeEntity("e", "p", "guid", "Episode", "https://example.test/audio"))
        db.library().putDistillation(DistillationEntity("d", "e", 0, 10000, excerpt = "Small daily routines help build lasting skills.", state = "succeeded"))
    }
    @Test fun `explanation stays tied to the saved source and reuses a completed report`() = runBlocking {
        val db = database()
        try {
            moment(db); var calls = 0
            val ai = object : AiGateway { override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit): AiText {
                calls++; assertEquals("Small daily routines help build lasting skills.", input)
                return AiText("Daily routines can make practice consistent. The excerpt suggests that repeated practice supports lasting skills.", "fixture")
            } }
            val requests = mutableListOf<String>(); val repo = MomentExplanation(db, ai, requests::add)
            val id = repo.create("d"); repo.run(id)
            assertEquals("succeeded", db.library().research(id)!!.state)
            assertEquals("transcript", db.library().research(id)!!.mode)
            assertEquals(id, repo.create("d")); assertEquals(1, calls); assertEquals(listOf(id), requests)
        } finally { db.close() }
    }
    @Test fun `invented external links are rejected and retries preserve the failed attempt`() = runBlocking {
        val db = database()
        try {
            moment(db)
            val ai = object : AiGateway { override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit) = AiText("This unsupported claim cites https://invented.example/source and should not be accepted.", "fixture") }
            val repo = MomentExplanation(db, ai, {})
            val first = repo.create("d"); repo.run(first)
            assertEquals("failed", db.library().research(first)!!.state)
            val retry = repo.create("d")
            assertNotEquals(first, retry); assertEquals(2, db.library().researches().size)
            assertTrue(db.library().distillation("d")!!.excerpt.isNotBlank())
        } finally { db.close() }
    }
    @Test fun `suggestions resolve directory entries into real feeds and history is opt in`() = runBlocking {
        val db = database()
        try { MockWebServer().use { server ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            val settings = AppSettings(context)
            settings.text("recommendations_state", "")
            db.library().putPodcast(PodcastEntity("followed", server.url("/followed").toString(), "A followed show"))
            db.library().putSuggestions(listOf(SuggestionEntity(server.url("/dismissed").toString(), "Dismissed", reason = "Earlier", dismissed = true)))
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/search")) return MockResponse().setBody(JSONObject().put("results", JSONArray(listOf("/good", "/bad", "/followed", "/dismissed").map {
                    JSONObject().put("collectionName", "Directory title").put("artistName", "Author").put("feedUrl", server.url(it).toString())
                })).toString())
                if (request.path == "/good") return MockResponse().setBody("""<rss><channel><title>Verified science show</title><item><guid>one</guid><title>Episode</title><enclosure url="${server.url("/audio")}"/></item></channel></rss>""")
                return MockResponse().setResponseCode(404)
            } }
            val inputs = mutableListOf<String>()
            val ai = object : AiGateway { override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit): AiText {
                inputs.add(input); return AiText("""{"queries":[{"query":"science","reason":"Science matches your stated interest."}]}""", "fixture")
            } }
            val requests = mutableListOf<Triple<String, String, Boolean>>()
            val http = OkHttpClient()
            val repo = Recommendations(db, settings, PodcastRepository(db, http, server.url("/search")), http, ai, { id, topic, history -> requests.add(Triple(id, topic, history)) })
            repo.request("science", false); repo.request("science", false)
            assertEquals(1, requests.size)
            val first = requests.single(); repo.run(first.first, first.second, first.third)
            assertEquals("succeeded", repo.state.first())
            assertFalse(JSONObject(inputs.single()).has("followedPodcastTitles"))
            val suggestions = db.library().suggestions().filter { !it.dismissed }
            assertEquals(listOf("Verified science show"), suggestions.map { it.title })
            repo.request("science", true)
            val next = requests.last(); repo.run(next.first, next.second, next.third)
            assertEquals("A followed show", JSONObject(inputs.last()).getJSONArray("followedPodcastTitles").getString(0))
        } } finally { db.close() }
    }
    /** #307 `dog breeds`: directory order, not relevance, used to place an audiobook feed (matched through one episode) among the dog shows. */
    @Test fun `suggestions rank by their own feed metadata with a grounded reason and keep artwork`() = runBlocking {
        val db = database()
        try { MockWebServer().use { server ->
            val settings = AppSettings(ApplicationProvider.getApplicationContext<Context>())
            settings.text("recommendations_state", "")
            fun entry(path: String, title: String, author: String, genre: String) = JSONObject().put("collectionName", title).put("artistName", author)
                .put("feedUrl", server.url(path).toString()).put("artworkUrl600", "https://images.example.test$path.jpg").put("primaryGenreName", genre)
            fun rss(title: String, description: String, image: String = "") = """<rss><channel><title>$title</title><description>$description</description>$image<item><guid>one</guid><title>Episode</title><enclosure url="${server.url("/audio")}"/></item></channel></rss>"""
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/search") && request.requestUrl!!.queryParameter("term") == "dog breed traits and care" ->
                    MockResponse().setBody(JSONObject().put("results", JSONArray(listOf(entry("/talk", "Dog Talk", "Heather Parsons", "Animais"),
                        entry("/audiobooks", "Never A Dull Moment With Audiobooks", "Book Buzz", "Educação para crianças")))).toString())
                request.path!!.startsWith("/search") -> MockResponse().setBody(JSONObject().put("results", JSONArray(listOf(entry("/breeds", "Dog Breeds", "Melany Benavides Porras", "Animais")))).toString())
                request.path == "/audiobooks" -> MockResponse().setBody(rss("Never A Dull Moment With Audiobooks", "Listen to full audiobooks for free with Audible standard. Bestsellers and new releases."))
                request.path == "/talk" -> MockResponse().setBody(rss("Dog Talk", "Join me on our adventures and we will talk about pet care, dog food, travel and everything dogs.", "<image><url>https://images.example.test/feed-talk.jpg</url></image>"))
                request.path == "/breeds" -> MockResponse().setBody(rss("Dog Breeds", "History, characteristics and personality of German Shepherds"))
                else -> MockResponse().setResponseCode(404)
            } }
            // An older-style answer still carries one reason per search; it describes the search, not any one podcast.
            val ai = object : AiGateway { override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit) =
                AiText("""{"queries":[{"query":"dog breed traits and care","reason":"Shows about dog breed traits and care."},{"query":"dog breeds"}]}""", "fixture") }
            val http = OkHttpClient(); val requests = mutableListOf<Triple<String, String, Boolean>>()
            val repo = Recommendations(db, settings, PodcastRepository(db, http, server.url("/search")), http, ai, { id, topic, history -> requests.add(Triple(id, topic, history)) })
            repo.request("dog breeds", false); requests.single().let { repo.run(it.first, it.second, it.third) }
            assertEquals(repo.error.first(), "succeeded", repo.state.first())
            val shown = db.library().observeSuggestions().first()
            assertEquals(listOf("Dog Breeds", "Dog Talk", "Never A Dull Moment With Audiobooks"), shown.map { it.title })
            assertEquals(listOf("Title mentions “dog” and “breeds”.", "Title and description mention “dog”; description mentions “care”.", ""), shown.map { it.reason })
            assertEquals(listOf("https://images.example.test/breeds.jpg", "https://images.example.test/feed-talk.jpg", "https://images.example.test/audiobooks.jpg"), shown.map { it.imageUrl })
        } } finally { db.close() }
    }
}
