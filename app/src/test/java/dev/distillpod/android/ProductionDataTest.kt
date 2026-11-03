package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import dev.distillpod.core.episodeId
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
class ProductionDataTest {
    private inline fun <R> AppDatabase.useDatabase(block: (AppDatabase) -> R): R = try { block(this) } finally { close() }
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()

    @Test fun `refresh updates remote metadata without losing progress captures or download state`() = runBlocking {
        database().useDatabase { db -> MockWebServer().use { server ->
            fun rss(title: String) = """<rss><channel><title>Show</title><item><guid>episode-one</guid><title>$title</title><enclosure url="${server.url("/audio.mp3")}"/></item></channel></rss>"""
            server.enqueue(MockResponse().setBody(rss("Original")).setHeader("ETag", "\"feed-1\""))
            val repo = PodcastRepository(db, OkHttpClient())
            val id = repo.subscribe(server.url("/feed").toString())
            val original = db.library().episodes().single()
            db.library().putEpisode(original.copy(positionMs = 12345, completed = false, localFile = "saved.audio", downloadState = "downloaded", mediaFingerprint = "audio-sha"))
            db.library().putDistillation(DistillationEntity("capture", original.id, 0, 12345, excerpt = "Saved source"))
            server.enqueue(MockResponse().setBody(rss("Updated")).setHeader("ETag", "\"feed-2\""))
            repo.refresh(id)
            val updated = db.library().episode(original.id)!!
            assertEquals("Updated", updated.title); assertEquals(12345, updated.positionMs)
            assertEquals("saved.audio", updated.localFile); assertEquals("audio-sha", updated.mediaFingerprint)
            assertEquals("Saved source", db.library().distillations().single().excerpt)
            server.takeRequest()
            assertEquals("\"feed-1\"", server.takeRequest().getHeader("If-None-Match"))
            server.enqueue(MockResponse().setResponseCode(304))
            repo.refresh(id)
            assertEquals(1, db.library().episodes().size)
        } }
    }

    @Test fun `failed refresh preserves cached library and repeated subscribe is idempotent`() = runBlocking {
        database().useDatabase { db -> MockWebServer().use { server ->
            val rss = """<rss><channel><title>Show</title><item><guid>same</guid><title>Saved</title><enclosure url="https://example.test/audio.mp3"/></item></channel></rss>"""
            val repo = PodcastRepository(db, OkHttpClient())
            server.enqueue(MockResponse().setBody(rss))
            val id = repo.subscribe(server.url("/feed").toString())
            server.enqueue(MockResponse().setBody(rss))
            assertEquals(id, repo.subscribe(server.url("/feed").toString()))
            server.enqueue(MockResponse().setResponseCode(503))
            assertTrue(runCatching { repo.refresh(id) }.isFailure)
            assertEquals(1, db.library().podcasts().size); assertEquals("Saved", db.library().episodes().single().title)
            assertNotNull(db.library().podcast(id)!!.error)
        } }
    }

    @Test fun `same GUID in different feeds remains distinct and invalid queue replacement rolls back`() = runBlocking {
        database().useDatabase { db ->
            val dao = db.library()
            for (id in listOf("a", "b")) {
                dao.putPodcast(PodcastEntity(id, "https://example.test/$id", id))
                dao.putEpisode(EpisodeEntity(episodeId(id, "same"), id, "same", id, "https://example.test/$id.mp3"))
            }
            assertEquals(2, dao.episodes().size)
            val first = dao.episodes().first().id
            dao.insertQueue(listOf(QueueEntryEntity(first, 0)))
            assertTrue(runCatching { db.withTransaction { dao.clearQueue(); dao.insertQueue(listOf(QueueEntryEntity("missing", 0))) } }.isFailure)
            assertEquals(first, dao.queue().single().episodeId)
        }
    }
    @Test fun `refresh preserves imported episode identifiers by feed scoped GUID`() = runBlocking {
        database().useDatabase { db -> MockWebServer().use { server ->
            val dao = db.library()
            dao.putPodcast(PodcastEntity("show", server.url("/feed").toString(), "Imported show"))
            dao.putEpisode(EpisodeEntity("legacy-id", "show", "same-guid", "Old title", server.url("/audio").toString(), positionMs = 23000))
            dao.putDistillation(DistillationEntity("moment", "legacy-id", 0, 23000, excerpt = "Saved source"))
            server.enqueue(MockResponse().setBody("""<rss><channel><title>Show</title><item><guid>same-guid</guid><title>New title</title><enclosure url="${server.url("/audio")}"/></item></channel></rss>"""))
            PodcastRepository(db, OkHttpClient()).refresh("show")
            assertEquals(1, dao.episodes().size)
            assertEquals("New title", dao.episode("legacy-id")!!.title)
            assertEquals(23000L, dao.episode("legacy-id")!!.positionMs)
            assertEquals("legacy-id", dao.distillation("moment")!!.episodeId)
        } }
    }
    @Test fun `changed enclosure discards range validators and invalidates dependent timing`() = runBlocking {
        database().useDatabase { db -> MockWebServer().use { server ->
            val dao = db.library()
            dao.putPodcast(PodcastEntity("p", server.url("/feed").toString(), "Show"))
            dao.putEpisode(EpisodeEntity("e", "p", "guid", "Episode", server.url("/old").toString(), positionMs = 23000,
                downloadState = "paused", downloadedBytes = 100, totalBytes = 200, etag = "\"old\"", lastModified = "old-date", mediaFingerprint = "old-sha"))
            dao.putTranscript(TranscriptEntity("e", "r", "old-sha", "tiny", "en", state = "ready"))
            dao.putAnalysis(AnalysisEntity("e", "r", state = "succeeded", summary = "Saved summary"))
            server.enqueue(MockResponse().setBody("""<rss><channel><title>Show</title><item><guid>guid</guid><title>Episode</title><enclosure url="${server.url("/new")}"/></item></channel></rss>"""))
            PodcastRepository(db, OkHttpClient()).refresh("p")
            val episode = dao.episode("e")!!
            assertNull(episode.etag); assertNull(episode.lastModified)
            assertEquals(0L, episode.downloadedBytes); assertEquals(23000L, episode.positionMs)
            assertEquals("stale", dao.transcript("e")!!.state)
            assertEquals("stale", dao.analysis("e")!!.state)
        } }
    }
    @Test fun `unused cache cleanup keeps owned partial queued and processing media`() = runBlocking {
        database().useDatabase { db ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            val active = ActiveFiles()
            val media = MediaRepository(context, db, AppSettings(context), ResumableDownloader(OkHttpClient()), active)
            val known = java.io.File(media.directory, "known.audio").apply { writeText("owned") }
            val partial = java.io.File(media.directory, "e.partial").apply { writeText("partial") }
            val queued = java.io.File(media.directory, "queued.audio").apply { writeText("queued") }
            val processing = java.io.File(media.directory, "processing.audio").apply { writeText("processing") }
            val orphan = java.io.File(media.directory, "orphan.audio").apply { writeText("unused") }
            db.library().putPodcast(PodcastEntity("p", "https://example.test/feed", "Show"))
            db.library().putEpisode(EpisodeEntity("e", "p", "g", "Episode", "https://example.test/audio", localFile = known.absolutePath, downloadState = "paused"))
            active.queued = setOf(queued.absolutePath); active.acquire(processing)
            assertEquals(6L, media.clearUnusedAudio())
            assertFalse(orphan.exists()); assertTrue(listOf(known, partial, queued, processing).all { it.exists() })
            active.release(processing); active.queued = emptySet()
        }
    }
}
