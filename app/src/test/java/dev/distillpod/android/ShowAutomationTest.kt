package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import dev.distillpod.android.work.DOWNLOAD_BLOCKED_MESSAGE
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShowAutomationTest {
    private fun fixture(block: suspend (AppDatabase, MockWebServer, PodcastRepository) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try { MockWebServer().use { server ->
            db.library().putPodcast(PodcastEntity("p", server.url("/rss").toString(), "Show"))
            block(db, server, PodcastRepository(db, OkHttpClient()))
        } } finally { db.close() }
    }
    private fun feed(vararg ids: String) = MockResponse().setBody("""<rss version="2.0"><channel><title>Show</title>${ids.joinToString("") {
        "<item><guid>$it</guid><title>$it</title><enclosure url=\"https://example.test/$it.mp3\" type=\"audio/mpeg\" length=\"100\"/></item>"
    }}</channel></rss>""")

    @Test fun `activation establishes baseline then discovers each future episode once`() = fixture { db, server, repo ->
        db.automation().putShow(ShowAutomationEntity("p", 1000))
        server.enqueue(feed("old")); repo.refresh("p")
        assertTrue(db.automation().show("p")!!.initialized)
        assertTrue(db.automation().jobs().isEmpty())
        server.enqueue(feed("old", "new")); repo.refresh("p")
        val job = db.automation().jobs().single()
        assertEquals("new", db.library().episode(job.episodeId)!!.guid)
        db.automation().putJob(job.copy(stage = "ready"))
        server.enqueue(feed("old", "new")); repo.refresh("p")
        assertEquals("ready", db.automation().jobs().single().stage)
        db.automation().disable("p"); db.automation().deleteJobs("p")
        server.enqueue(feed("old", "new", "disabled")); repo.refresh("p")
        assertTrue(db.automation().jobs().isEmpty())
        db.automation().putShow(ShowAutomationEntity("p", 2000))
        server.enqueue(feed("old", "new", "disabled")); repo.refresh("p")
        assertTrue(db.automation().jobs().isEmpty())
    }
    @Test fun `failed initial refresh does not admit backlog and 304 can initialize cached baseline`() = fixture { db, server, repo ->
        db.automation().putShow(ShowAutomationEntity("p", 1000))
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(runCatching { repo.refresh("p") }.isFailure)
        assertFalse(db.automation().show("p")!!.initialized)
        server.enqueue(feed("backlog")); repo.refresh("p")
        assertTrue(db.automation().jobs().isEmpty())
        db.automation().putShow(ShowAutomationEntity("p", 2000))
        server.enqueue(MockResponse().setResponseCode(304)); repo.refresh("p")
        assertTrue(db.automation().show("p")!!.initialized)
        server.enqueue(feed("backlog", "future")); repo.refresh("p")
        assertEquals(1, db.automation().jobs().size)
    }
    @Test fun `player eligibility rejects incomplete online and stale analysis`() {
        val ep = EpisodeEntity("e", "p", "g", "Episode", "https://example.test/a", localFile = "/audio", mediaFingerprint = "f")
        val transcript = TranscriptEntity("e", "r", "f", "tiny", "en", state = "ready")
        val analysis = AnalysisEntity("e", "r", state = "succeeded")
        assertTrue(canSkipMarkedAds(ep, transcript, analysis))
        assertFalse(canSkipMarkedAds(ep.copy(localFile = null), transcript, analysis))
        assertFalse(canSkipMarkedAds(ep, transcript.copy(state = "partial"), analysis))
        assertFalse(canSkipMarkedAds(ep, transcript.copy(source = "online"), analysis))
        assertFalse(canSkipMarkedAds(ep.copy(mediaFingerprint = "changed"), transcript, analysis))
        assertFalse(canSkipMarkedAds(ep, transcript, analysis.copy(revision = "old")))
        assertFalse(canSkipMarkedAds(ep, transcript, analysis.copy(state = "running")))
    }
    @Test fun `pipeline resumes from persisted stages without repeating completed work`() = fixture { db, _, _ ->
        val file = java.io.File.createTempFile("episode", ".mp3")
        try {
            val ep = EpisodeEntity("e", "p", "g", "Episode", "https://example.test/audio", mediaFingerprint = "f")
            db.library().putEpisode(ep)
            db.automation().putShow(ShowAutomationEntity("p", 0, true))
            db.automation().insertJob(EpisodeAutomationEntity("e", "p"))
            val actions = mutableListOf<String>()
            var connected = false
            fun processor() = AutomationProcessor(db, { connected }, { id ->
                actions.add("download")
                db.library().putEpisode(ep.copy(downloadState = "queued"))
            }, { id ->
                actions.add("transcribe")
                db.library().putTranscript(TranscriptEntity(id, "r", "f", "tiny", "en"))
            }, { id ->
                actions.add("analyze")
                db.library().putAnalysis(AnalysisEntity(id, "r"))
            })
            processor().advance(); processor().advance()
            assertEquals(listOf("download"), actions)
            db.library().putEpisode(ep.copy(localFile = file.path, downloadState = "downloaded"))
            processor().advance(); processor().advance()
            assertEquals(listOf("download", "transcribe"), actions)
            val transcript = db.library().transcript("e")!!
            db.library().putTranscript(transcript.copy(state = "paused"))
            processor().advance()
            assertEquals(2, actions.size)
            db.library().putTranscript(transcript.copy(state = "ready"))
            processor().advance()
            assertEquals("waiting_auth", db.automation().jobs().single().stage)
            connected = true
            processor().advance(); processor().advance()
            assertEquals(listOf("download", "transcribe", "analyze"), actions)
            db.library().putAnalysis(db.library().analysis("e")!!.copy(state = "succeeded"))
            processor().advance(); processor().advance()
            assertEquals("ready", db.automation().jobs().single().stage)
            assertEquals(3, actions.size)
        } finally { file.delete() }
    }
    @Test fun `disabled or unfollowed shows do not advance pending work`() = fixture { db, _, _ ->
        db.library().putEpisode(EpisodeEntity("e", "p", "g", "Episode", "https://example.test/a"))
        db.automation().insertJob(EpisodeAutomationEntity("e", "p"))
        val processor = AutomationProcessor(db, { true }, { error("Unexpected download") }, { error("Unexpected transcript") }, { error("Unexpected analysis") })
        processor.advance()
        db.automation().putShow(ShowAutomationEntity("p", 0, true))
        db.library().putPodcast(db.library().podcast("p")!!.copy(subscribed = false))
        processor.advance()
        assertEquals("queued", db.automation().jobs().single().stage)
    }

    @Test fun `episode ad opt-in preserves review exclusions and cannot enable stale ranges`() = fixture { db, _, _ ->
        db.library().putEpisode(EpisodeEntity("e", "p", "g", "Episode", "https://example.test/a", localFile = "/audio", mediaFingerprint = "f"))
        db.library().putTranscript(TranscriptEntity("e", "r", "f", "tiny", "en", state = "ready"))
        db.library().putAnalysis(AnalysisEntity("e", "r", state = "succeeded", adsJson = """[{"startMs":1000,"endMs":5000,"approved":false}]"""))
        setEpisodeAdSkipping(db, "e", true)
        assertTrue(db.automation().ad("e")!!.enabled)
        assertTrue(org.json.JSONArray(db.library().analysis("e")!!.adsJson).getJSONObject(0).getBoolean("approved"))
        db.library().putAnalysis(db.library().analysis("e")!!.copy(adsJson = """[{"startMs":1000,"endMs":5000,"approved":false}]"""))
        setEpisodeAdSkipping(db, "e", false); setEpisodeAdSkipping(db, "e", true)
        assertFalse(org.json.JSONArray(db.library().analysis("e")!!.adsJson).getJSONObject(0).getBoolean("approved"))
        assertNull(db.automation().ad("another-episode"))
        val newAnalysis = db.library().analysis("e")!!.copy(attempt = "new-analysis")
        db.library().putAnalysis(newAnalysis)
        assertNotEquals(adSelectionRevision(newAnalysis), db.automation().ad("e")!!.revision)
        db.library().putTranscript(db.library().transcript("e")!!.copy(revision = "new"))
        assertTrue(runCatching { setEpisodeAdSkipping(db, "e", true) }.isFailure)
    }
    @Test fun `old dated episodes newly added to a feed do not trigger automatic processing`() = fixture { db, server, repo ->
        db.automation().putShow(ShowAutomationEntity("p", System.currentTimeMillis(), true))
        server.enqueue(MockResponse().setBody("""<rss version="2.0"><channel><title>Show</title><item><guid>archive</guid><title>Archive</title><pubDate>Mon, 01 Jan 2024 00:00:00 GMT</pubDate><enclosure url="https://example.test/archive.mp3" type="audio/mpeg" length="100"/></item></channel></rss>"""))
        repo.refresh("p")
        assertEquals(1, db.library().episodesForPodcast("p").size)
        assertTrue(db.automation().jobs().isEmpty())
    }

    @Test fun `automatic discovery preserves a manual pause until explicit retry`() = fixture { db, _, _ ->
        val ep = EpisodeEntity("e", "p", "g", "Episode", "https://example.test/a", downloadState = "paused")
        db.library().putEpisode(ep)
        db.automation().putShow(ShowAutomationEntity("p", 0, true))
        db.automation().insertJob(EpisodeAutomationEntity("e", "p"))
        var downloads = 0
        val processor = AutomationProcessor(db, { true }, { downloads++ }, {}, {})
        processor.advance()
        assertEquals(0, downloads)
        db.automation().putJob(EpisodeAutomationEntity("e", "p", "retry"))
        processor.advance()
        assertEquals(1, downloads)
    }

    private suspend fun AppDatabase.stage(id: String) = automation().jobs().single { it.episodeId == id }.stage

    @Test fun `a blocked job does not stop later episodes while running work stays serialized`() = fixture { db, _, _ ->
        val file = java.io.File.createTempFile("episode", ".mp3")
        try {
            db.automation().putShow(ShowAutomationEntity("p", 0, true))
            // Android refused the background download start (#138); only the user can resume it.
            db.library().putEpisode(EpisodeEntity("a", "p", "a", "A", "https://example.test/a", downloadState = "paused", error = DOWNLOAD_BLOCKED_MESSAGE))
            // Transcribed, but its analysis was left without running work.
            db.library().putEpisode(EpisodeEntity("b", "p", "b", "B", "https://example.test/b", localFile = file.path, downloadState = "downloaded", mediaFingerprint = "f"))
            db.library().putTranscript(TranscriptEntity("b", "r", "f", "tiny", "en", state = "ready"))
            db.library().putAnalysis(AnalysisEntity("b", "r", state = "stale"))
            db.library().putEpisode(EpisodeEntity("c", "p", "c", "C", "https://example.test/c"))
            db.library().putEpisode(EpisodeEntity("d", "p", "d", "D", "https://example.test/d"))
            db.automation().insertJob(EpisodeAutomationEntity("a", "p"))
            db.automation().insertJob(EpisodeAutomationEntity("b", "p", "analyzing"))
            db.automation().insertJob(EpisodeAutomationEntity("c", "p"))
            db.automation().insertJob(EpisodeAutomationEntity("d", "p"))
            val downloads = mutableListOf<String>()
            val processor = AutomationProcessor(db, { true }, { id ->
                downloads += id
                db.library().putEpisode(db.library().episode(id)!!.copy(downloadState = "queued"))
            }, { error("Unexpected transcript") }, { error("Unexpected analysis") })
            processor.advance()
            assertEquals(listOf("c"), downloads)
            assertEquals("blocked", db.stage("a"))
            assertEquals("blocked", db.stage("b"))
            assertEquals("downloading", db.stage("c"))
            // D waits for C's active download; blocked jobs do not count as pending work.
            assertEquals("queued", db.stage("d"))
            processor.advance()
            assertEquals(listOf("c"), downloads)
        } finally { file.delete() }
    }

    @Test fun `clearing a blocker resumes the job`() = fixture { db, _, _ ->
        val file = java.io.File.createTempFile("episode", ".mp3")
        try {
            db.automation().putShow(ShowAutomationEntity("p", 0, true))
            val a = EpisodeEntity("a", "p", "a", "A", "https://example.test/a", downloadState = "paused", error = DOWNLOAD_BLOCKED_MESSAGE, mediaFingerprint = "f")
            db.library().putEpisode(a)
            db.library().putEpisode(EpisodeEntity("b", "p", "b", "B", "https://example.test/b"))
            db.automation().insertJob(EpisodeAutomationEntity("a", "p"))
            db.automation().insertJob(EpisodeAutomationEntity("b", "p"))
            val actions = mutableListOf<String>()
            val processor = AutomationProcessor(db, { true }, { id ->
                actions += "download:$id"
                db.library().putEpisode(db.library().episode(id)!!.copy(downloadState = "queued"))
            }, { id ->
                actions += "transcribe:$id"
                db.library().putTranscript(TranscriptEntity(id, "r", "f", "tiny", "en", state = "waiting_model"))
            }, { error("Unexpected analysis") })
            processor.advance()
            assertEquals(listOf("download:b"), actions)
            assertEquals("blocked", db.stage("a"))
            // Resume in the app re-queues A's request itself.
            db.library().putEpisode(a.copy(downloadState = "queued", error = null))
            processor.advance()
            assertEquals("downloading", db.stage("a"))
            assertEquals(listOf("download:b"), actions)
            db.library().putEpisode(a.copy(localFile = file.path, downloadState = "downloaded", error = null))
            processor.advance()
            assertEquals(listOf("download:b", "transcribe:a"), actions)
            // Without a model the transcript waits; later jobs are not held behind it.
            assertEquals("blocked", db.stage("a"))
            db.library().putTranscript(db.library().transcript("a")!!.copy(state = "queued"))
            processor.advance()
            assertEquals("transcribing", db.stage("a"))
            assertEquals(listOf("download:b", "transcribe:a"), actions)
        } finally { file.delete() }
    }

}
