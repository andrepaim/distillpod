package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.ai.*
import dev.distillpod.android.data.*
import dev.distillpod.android.sync.SyncPublicFeedEntity
import dev.distillpod.core.episodeId
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnlineTranscriptsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun database() = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val ep = EpisodeEntity("e", "p", "guid", "Practice makes progress", "https://example.test/episode.mp3?edition=1",
        durationMs = 10000, mediaFingerprint = "audio-sha")
    private val segments = """[{"start":0,"end":5,"text":"Consistent practice builds useful skills."},{"start":5,"end":10,"text":"Small steps add up over time."}]"""
    private fun record(audio: String = ep.audioUrl) = JSONObject().put("id", "public-id").put("audio_url", audio)
        .put("duration", 10).put("language", "English").put("created_at", "2026-11-05").put("segments", JSONArray(segments))
    private fun envelope(data: Any) = MockResponse().setBody(JSONObject().put("ok", true).put("data", data).toString())
    private suspend fun seed(db: AppDatabase, public: Boolean = true): TranscriptEntity {
        db.library().putPodcast(PodcastEntity("p", "https://example.test/feed", "Show"))
        db.library().putEpisode(ep)
        if (public) db.sync().approve(SyncPublicFeedEntity("p"))
        return TranscriptEntity("e", "attempt", ep.mediaFingerprint, "tiny", "auto", state = "processing", lookupOnline = true)
            .also { db.library().putTranscript(it) }
    }
    private fun remote() = ExistingTranscript("public-id", ep.audioUrl, 10000, "English", "2026-11-05", segments)

    @Test fun `read-only search verifies exact enclosure and reads documented nested detail`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(envelope(JSONArray().put(record("https://other.test/different.mp3")).put(record())))
            server.enqueue(envelope(JSONObject().put("transcription", record())))
            val result = PodcastTranscriptClient(OkHttpClient(), server.url("/api/v1/")).find(ep)!!
            assertEquals(10000L, result.durationMs)
            val search = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("GET", search.method); assertEquals("/api/v1/transcriptions", search.requestUrl!!.encodedPath)
            assertEquals(ep.title, search.requestUrl!!.queryParameter("q"))
            assertNull(search.requestUrl!!.queryParameter("audio_url")); assertNull(search.getHeader("Authorization"))
            val detail = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("GET", detail.method); assertEquals("segments", detail.requestUrl!!.queryParameter("include"))
            assertEquals(2, server.requestCount)
            assertEquals(5000L, decodeOnlineSegments("e", "r", result.segmentsJson, result.durationMs)[1].startMs)
        }
    }
    @Test fun `title matches alone and changed URL query never adopt another recording`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(envelope(JSONArray().put(record(ep.audioUrl.replace("edition=1", "edition=2")))))
            assertNull(PodcastTranscriptClient(OkHttpClient(), server.url("/")).find(ep))
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `detail identity duration untimed and truncated transcripts are rejected`() = runBlocking {
        for (detail in listOf(record("https://other.test/audio.mp3"), record().put("duration", 200),
            record().put("segments", JSONArray("""[{"start":0,"end":0,"text":"Untimed"}]""")),
            record().put("segments", JSONArray("""[{"start":0,"end":1,"text":"Preview only"}]""")))) {
            MockWebServer().use { server ->
                server.enqueue(envelope(JSONArray().put(record())))
                server.enqueue(envelope(JSONObject().put("transcription", detail)))
                assertNull(PodcastTranscriptClient(OkHttpClient(), server.url("/")).find(ep))
            }
        }
    }
    @Test fun `oversized response is rejected before JSON allocation`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(2_000_001)))
            assertTrue(runCatching { PodcastTranscriptClient(OkHttpClient(), server.url("/")).find(ep) }.isFailure)
        }
    }
    @Test fun `opt-in public provenance and network are required before a lookup`() = runBlocking {
        val db = database()
        try {
            val row = seed(db, false); val settings = AppSettings(context); var requests = 0
            val provider = ExistingTranscriptProvider { requests++; remote() }
            settings.flag("onlineTranscripts", false)
            assertFalse(OnlineTranscriptRepository(db, settings, provider).lookup(ep, row))
            settings.flag("onlineTranscripts", true)
            assertFalse(OnlineTranscriptRepository(db, settings, provider).lookup(ep, row))
            db.sync().approve(SyncPublicFeedEntity("p"))
            assertFalse(OnlineTranscriptRepository(db, settings, provider, { false }).lookup(ep, row))
            assertEquals(0, requests)
            assertTrue(OnlineTranscriptRepository(db, settings, provider).lookup(ep, row))
            assertEquals(1, requests); assertEquals("online", db.library().transcript("e")!!.source)
        } finally { db.close() }
    }
    @Test fun `service errors are cached temporarily and never fail local fallback`() = runBlocking {
        val db = database()
        try {
            val row = seed(db); val settings = AppSettings(context); settings.flag("onlineTranscripts", true)
            var time = 1000L; var requests = 0
            val repo = OnlineTranscriptRepository(db, settings, ExistingTranscriptProvider { requests++; throw java.io.IOException() }, now = { time })
            assertFalse(repo.lookup(ep, row)); assertFalse(repo.lookup(ep, row)); assertEquals(1, requests)
            assertEquals("processing", db.library().transcript("e")!!.state)
            time += 900001
            assertFalse(repo.lookup(ep, row)); assertEquals(2, requests)
        } finally { db.close() }
    }
    @Test fun `miss cache is invalidated by changed audio identity`() = runBlocking {
        val db = database()
        try {
            val row = seed(db); val settings = AppSettings(context); settings.flag("onlineTranscripts", true)
            var requests = 0
            val repo = OnlineTranscriptRepository(db, settings, ExistingTranscriptProvider { requests++; null })
            assertFalse(repo.lookup(ep, row)); assertFalse(repo.lookup(ep, row)); assertEquals(1, requests)
            val changed = ep.copy(audioUrl = "https://example.test/new.mp3")
            db.library().putEpisode(changed)
            assertFalse(repo.lookup(changed, row)); assertEquals(2, requests)
        } finally { db.close() }
    }
    @Test fun `cancellation pause source changes and consent withdrawal reject late adoption`() = runBlocking {
        for (change in listOf("cancel", "pause", "source", "consent")) {
            val db = database()
            try {
                val row = seed(db); val settings = AppSettings(context); settings.flag("onlineTranscripts", true)
                val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                val repo = OnlineTranscriptRepository(db, settings, ExistingTranscriptProvider {
                    started.complete(Unit); release.await(); remote()
                })
                val job = async { repo.lookup(ep, row) }
                started.await()
                when (change) {
                    "cancel" -> job.cancelAndJoin()
                    "pause" -> db.library().putTranscript(row.copy(state = "paused"))
                    "source" -> db.library().putEpisode(ep.copy(mediaFingerprint = "changed"))
                    "consent" -> settings.flag("onlineTranscripts", false)
                }
                release.complete(Unit)
                if (change != "cancel") assertFalse(job.await())
                assertNull(db.library().onlineTranscript("e")); assertNull(db.library().transcriptLookup("e"))
            } finally { db.close() }
        }
    }
    @Test fun `online text supports chat survives interrupted local replacement and does not hydrate audio captures`() = runBlocking {
        val db = database()
        try {
            val row = seed(db); val settings = AppSettings(context); settings.flag("onlineTranscripts", true)
            assertTrue(OnlineTranscriptRepository(db, settings, ExistingTranscriptProvider { remote() }).lookup(ep, row))
            val selected = EpisodeTextResolver(db).complete("e")!!
            val ai = object : AiGateway {
                override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit) =
                    AiText("""{"answer":"Practice builds skills.","supported":true,"sources":[0]}""", "fixture")
            }
            val understanding = EpisodeUnderstanding(db, ai, {}, {})
            val reply = understanding.ask("e", "What does practice do?")
            understanding.runReply(reply)
            assertEquals("succeeded", db.library().message(reply)!!.state)
            var localRequested = false
            val captures = CaptureRepository(db, ai, { true }, { localRequested = true }, {})
            val capture = captures.capture("e", 9000, true)
            assertTrue(localRequested); assertEquals("waiting_transcript", db.library().distillation(capture)!!.state)
            db.library().putTranscript(row.copy(revision = "local-replacement", state = "paused"))
            db.library().deleteSegments("e")
            assertEquals(selected.revision, EpisodeTextResolver(db).complete("e")!!.revision)
            assertEquals(2, EpisodeTextResolver(db).segments("e", selected.revision).size)
            db.library().putEpisode(ep.copy(audioUrl = "https://example.test/changed.mp3", mediaFingerprint = "changed"))
            assertNull(EpisodeTextResolver(db).complete("e"))
        } finally { db.close() }
    }
    @Test fun `online cache and interrupted jobs round trip through backup without enabling network work`() = runBlocking {
        val source = database(); val target = database()
        try {
            val row = seed(source); val settings = AppSettings(context); settings.flag("onlineTranscripts", true)
            OnlineTranscriptRepository(source, settings, ExistingTranscriptProvider { remote() }).lookup(ep, row)
            source.library().putTranscript(row.copy(state = "queued"))
            val bytes = LibraryBackup(source).export()
            val backup = LibraryBackup(target)
            backup.merge(backup.preview(bytes.inputStream()))
            assertNotNull(EpisodeTextResolver(target).complete("e"))
            assertFalse(target.library().transcript("e")!!.lookupOnline)
            assertEquals("paused", target.library().transcript("e")!!.state)
            assertNull(target.library().transcriptLookup("e"))
        } finally { source.close(); target.close() }
    }
    @Test fun `rate limiting honors retry after and malformed results fall back safely`() = runBlocking {
        val db = database()
        try {
            val row = seed(db); val settings = AppSettings(context); settings.flag("onlineTranscripts", true)
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "3600"))
                val repo = OnlineTranscriptRepository(db, settings, PodcastTranscriptClient(OkHttpClient(), server.url("/")), now = { 1000 })
                assertFalse(repo.lookup(ep, row)); assertFalse(repo.lookup(ep, row))
                assertEquals(1, server.requestCount)
                assertEquals(3601000L, db.library().transcriptLookup("e")!!.retryAt)
            }
            val invalid = OnlineTranscriptRepository(db, settings, ExistingTranscriptProvider { remote().copy(segmentsJson = "[]") }, now = { 4000000 })
            assertFalse(invalid.lookup(ep, row)); assertNull(db.library().onlineTranscript("e"))
            assertEquals("processing", db.library().transcript("e")!!.state)
        } finally { db.close() }
    }
    @Test fun `cancelling lookup cancels the pending HTTP read`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val request = async { PodcastTranscriptClient(OkHttpClient(), server.url("/")).find(ep) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
            withTimeout(2000) { request.cancelAndJoin() }
            assertTrue(request.isCancelled)
        }
    }
    private suspend fun isolatedGraph(): AppGraph {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context,
            androidx.work.Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor()).build())
        val graph = AppGraph(context)
        graph.scope.cancel()
        withTimeout(5000) {
            while (!graph.scope.coroutineContext[Job]!!.isCompleted) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                delay(10)
            }
        }
        return graph
    }
    @Test fun `transcription pipeline uses online hit before models and falls back on a miss`() = runBlocking {
        val graph = isolatedGraph()
        try {
            graph.settings.flag("onlineTranscripts", true)
            for (hit in listOf(true, false)) {
                withContext(Dispatchers.IO) { graph.db.clearAllTables() }
                val row = seed(graph.db)
                val online = OnlineTranscriptRepository(graph.db, graph.settings, ExistingTranscriptProvider { if (hit) remote() else null })
                val transcription = TranscriptionRepository(graph, online::lookup)
                transcription.process("e", row.revision, TranscriptExecution({ false }, {}, false))
                val result = graph.db.library().transcript("e")!!
                assertEquals(if (hit) "ready" else "waiting_model", result.state)
                assertEquals(if (hit) "online" else "whisper", result.source)
            }
        } finally { graph.db.close() }
    }
    @Test fun `pausing and resuming a local replacement keeps local intent and the online reader`() = runBlocking {
        val graph = isolatedGraph()
        try {
            val row = seed(graph.db); graph.settings.flag("onlineTranscripts", true)
            OnlineTranscriptRepository(graph.db, graph.settings, ExistingTranscriptProvider { remote() }).lookup(ep, row)
            graph.transcription.prepare("e", forceLocal = true)
            val replacement = graph.db.library().transcript("e")!!
            assertFalse(replacement.lookupOnline); assertTrue(replacement.replaceOnline)
            graph.transcription.pause("e")
            graph.transcription.prepare("e")
            val resumed = graph.db.library().transcript("e")!!
            assertEquals("queued", resumed.state); assertEquals(replacement.revision, resumed.revision)
            assertFalse(resumed.lookupOnline)
            assertEquals("online", EpisodeTextResolver(graph.db).complete("e")!!.source)
        } finally { graph.db.close() }
    }
    @Test fun `bad timing never becomes a seek target`() {
        for (json in listOf("""[{"start":-1,"end":10,"text":"bad"}]""", """[{"start":5,"end":3,"text":"bad"}]""",
            """[{"start":0,"end":1000000,"text":"bad"}]""", """[{"start":5,"end":10,"text":"ok"},{"start":1,"end":4,"text":"backwards"}]""")) {
            assertTrue(runCatching { decodeOnlineSegments("e", "r", json, 10000) }.isFailure)
        }
    }
}
