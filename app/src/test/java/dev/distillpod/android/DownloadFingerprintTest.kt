package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import dev.distillpod.core.episodeId
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #245: the enclosure-derived fingerprint is provisional until the first completed download. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadFingerprintTest {
    @Test fun firstDownloadKeepsWorkMadeFromTheProvisionalEnclosureIdentity() = runBlocking {
        fixture { db, media, server ->
            val dao = db.library()
            val provisional = episodeId("enclosure", server.url("/audio").toString())
            dao.putTranscript(TranscriptEntity("e", "r1", provisional, "publisher", "en", source = "publisher", state = "ready", durationMs = 60_000, processedMs = 60_000))
            dao.putAnalysis(AnalysisEntity("e", "r1", state = "succeeded", summary = "Summary"))
            dao.putMessage(MessageEntity("m", "e", "assistant", "Answer", revision = "r1", fingerprint = provisional))
            dao.putDistillation(DistillationEntity("moment", "e", 0, 30_000, insight = "Insight", revision = "r1", fingerprint = provisional, state = "succeeded", attempt = "a"))
            dao.putMomentAttempt(MomentAttemptEntity("a", "moment", JSONObject().put("revision", "r1").put("fingerprint", provisional).toString()))
            dao.putDistillation(DistillationEntity("unverified", "e", 0, 30_000, fingerprint = "unverified:e", state = "saved"))
            server.enqueue(MockResponse().setBody("same audio"))

            media.ensureDownloaded("e")

            val hash = dao.episode("e")!!.mediaFingerprint
            assertNotEquals(provisional, hash)
            val transcript = dao.transcript("e")!!
            assertEquals("ready", transcript.state)
            assertNull(transcript.error)
            assertEquals("r1", transcript.revision)
            assertEquals(hash, transcript.fingerprint)
            assertEquals("succeeded", dao.analysis("e")!!.state)
            assertEquals(hash, dao.messages().single().fingerprint)
            assertEquals(hash, dao.distillation("moment")!!.fingerprint)
            assertEquals(hash, JSONObject(dao.momentAttempt("a")!!.contextJson).getString("fingerprint"))
            assertEquals("unverified:e", dao.distillation("unverified")!!.fingerprint)
            assertEquals(transcript, EpisodeTextResolver(db).complete("e"))
        }
    }

    @Test fun firstDownloadKeepsTheTranscriptAnExplicitLocalReplacementKeepsReadable() = runBlocking {
        fixture { db, media, server ->
            val dao = db.library()
            val provisional = episodeId("enclosure", server.url("/audio").toString())
            // "Transcribe locally again" (#229) keeps r1 readable while the replacement r2 is prepared.
            dao.putPreviousTranscript(PreviousTranscriptEntity("e", "r1", provisional, "publisher", "en", "publisher", "ready", 60_000, 60_000))
            dao.putTranscript(TranscriptEntity("e", "r2", provisional, "base", "en", source = "local", state = "queued", durationMs = 60_000))
            server.enqueue(MockResponse().setBody("same audio"))

            media.ensureDownloaded("e")

            val episode = dao.episode("e")!!
            val kept = dao.previousTranscript("e")!!
            assertEquals(episode.mediaFingerprint, kept.fingerprint)
            assertEquals("r1", kept.revision)
            assertEquals("r1", EpisodeTextResolver(db).complete("e")!!.revision)
            assertEquals("r1", effectiveTranscript(episode, dao.transcript("e"), kept, null)!!.revision)
            assertEquals(episode.mediaFingerprint, dao.transcript("e")!!.fingerprint)
        }
    }

    @Test fun firstDownloadLeavesAKeptTranscriptOfAnotherSourceUnreadable() = runBlocking {
        fixture { db, media, server ->
            val dao = db.library()
            val other = episodeId("enclosure", "https://old.example.test/audio")
            dao.putPreviousTranscript(PreviousTranscriptEntity("e", "r1", other, "publisher", "en", "publisher", "ready", 60_000, 60_000))
            server.enqueue(MockResponse().setBody("audio"))

            media.ensureDownloaded("e")

            assertEquals(other, dao.previousTranscript("e")!!.fingerprint)
            assertNull(EpisodeTextResolver(db).complete("e"))
        }
    }

    @Test fun laterDownloadWithDifferentBytesStillMarksTheSourceStale() = runBlocking {
        fixture { db, media, server ->
            val dao = db.library()
            val provisional = episodeId("enclosure", server.url("/audio").toString())
            dao.putTranscript(TranscriptEntity("e", "r1", provisional, "publisher", "en", source = "publisher", state = "ready", durationMs = 60_000, processedMs = 60_000))
            dao.putAnalysis(AnalysisEntity("e", "r1", state = "succeeded", summary = "Summary"))
            server.enqueue(MockResponse().setBody("first audio"))
            File(media.ensureDownloaded("e").absolutePath).delete()
            val first = dao.episode("e")!!
            assertEquals("ready", dao.transcript("e")!!.state)
            dao.putEpisode(first.copy(localFile = null, downloadState = "none", downloadedBytes = 0, totalBytes = 0, etag = null))
            server.enqueue(MockResponse().setBody("dynamically inserted ad"))

            media.ensureDownloaded("e")

            assertNotEquals(first.mediaFingerprint, dao.episode("e")!!.mediaFingerprint)
            assertEquals("stale", dao.transcript("e")!!.state)
            assertEquals(first.mediaFingerprint, dao.transcript("e")!!.fingerprint)
            assertEquals("stale", dao.analysis("e")!!.state)
        }
    }

    @Test fun firstDownloadStillMarksATranscriptOfAnotherSourceStale() = runBlocking {
        fixture { db, media, server ->
            val dao = db.library()
            dao.putTranscript(TranscriptEntity("e", "r1", episodeId("enclosure", "https://old.example.test/audio"), "publisher", "en",
                source = "publisher", state = "ready", durationMs = 60_000, processedMs = 60_000))
            dao.putAnalysis(AnalysisEntity("e", "r1", state = "succeeded", summary = "Summary"))
            server.enqueue(MockResponse().setBody("audio"))

            media.ensureDownloaded("e")

            assertEquals("stale", dao.transcript("e")!!.state)
            assertEquals("stale", dao.analysis("e")!!.state)
        }
    }

    private suspend fun fixture(block: suspend (AppDatabase, MediaRepository, MockWebServer) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val settings = AppSettings(context)
        val media = MediaRepository(context, db, settings, ResumableDownloader(OkHttpClient()), ActiveFiles())
        // Robolectric's disposable application storage only. Never device storage.
        media.directory.listFiles()?.forEach { it.delete() }
        settings.storage(1_000_000_000)
        MockWebServer().use { server ->
            try {
                val url = server.url("/audio").toString()
                db.library().putPodcast(PodcastEntity("p", "https://example.test/feed", "Show"))
                db.library().putEpisode(EpisodeEntity("e", "p", "g", "Episode", url, mediaFingerprint = episodeId("enclosure", url)))
                block(db, media, server)
            } finally { db.close() }
        }
    }
}
