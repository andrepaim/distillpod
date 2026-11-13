package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import dev.distillpod.android.sync.PortableLibrarySync
import dev.distillpod.android.sync.SyncPublicFeedEntity
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #152: a feed refresh must not undo the duration the player measured for the same enclosure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeasuredDurationRefreshTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun database() = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val segments = """[{"start":0,"end":900,"text":"First half."},{"start":900,"end":1920,"text":"Second half."}]"""

    private fun rss(audio: String, duration: String?) = """<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><title>Show</title>
        <item><guid>episode-one</guid><title>Episode</title><enclosure url="$audio" type="audio/mpeg"/>${duration?.let { "<itunes:duration>$it</itunes:duration>" } ?: ""}</item>
        </channel></rss>"""

    private fun fixture(block: suspend (AppDatabase, MockWebServer, PodcastRepository) -> Unit) = runBlocking {
        val db = database()
        try { MockWebServer().use { server -> block(db, server, PodcastRepository(db, OkHttpClient())) } } finally { db.close() }
    }
    private suspend fun subscribe(db: AppDatabase, server: MockWebServer, repo: PodcastRepository, audio: String, duration: String?): EpisodeEntity {
        server.enqueue(MockResponse().setBody(rss(audio, duration)))
        repo.subscribe(server.url("/feed").toString())
        return db.library().episodes().single()
    }
    private suspend fun refresh(db: AppDatabase, server: MockWebServer, repo: PodcastRepository, audio: String, duration: String?): EpisodeEntity {
        server.enqueue(MockResponse().setBody(rss(audio, duration)))
        repo.refresh(db.library().podcasts().single().id)
        return db.library().episodes().single()
    }
    /** The production checkpoint path: the service passes the player's duration for the current item. */
    private suspend fun play(db: AppDatabase, id: String, position: Long, playerDuration: Long) =
        PortableLibrarySync(db).checkpoint(id, position, playerDuration, false, 5_000, "session", 0, force = true)

    @Test fun `refresh keeps the player's measured duration for an unchanged enclosure`() = fixture { db, server, repo ->
        val declared = subscribe(db, server, repo, "https://example.test/audio.mp3", "30:00")
        assertEquals(1_800_000L, declared.durationMs)
        play(db, declared.id, 600_000, 1_920_000)
        assertEquals(1_920_000L, db.library().episode(declared.id)!!.durationMs)
        // Feeds with dynamic ad insertion keep declaring (or re-declaring) a different length.
        for (feedValue in listOf("30:00", "31:00", null)) {
            val refreshed = refresh(db, server, repo, "https://example.test/audio.mp3", feedValue)
            assertEquals("declared $feedValue", 1_920_000L, refreshed.durationMs)
            assertEquals(600_000L, refreshed.positionMs)
        }
    }

    @Test fun `never played episodes still follow the feed's declared duration`() = fixture { db, server, repo ->
        subscribe(db, server, repo, "https://example.test/audio.mp3", "30:00")
        assertEquals(1_860_000L, refresh(db, server, repo, "https://example.test/audio.mp3", "31:00").durationMs)
        // No declaration keeps the last known value, as before.
        assertEquals(1_860_000L, refresh(db, server, repo, "https://example.test/audio.mp3", null).durationMs)
    }

    @Test fun `a changed enclosure uses the new declared duration, or unknown when none is declared`() = fixture { db, server, repo ->
        val first = subscribe(db, server, repo, "https://example.test/audio.mp3", "30:00")
        play(db, first.id, 600_000, 1_920_000)
        val republished = refresh(db, server, repo, "https://example.test/audio-v2.mp3", "25:00")
        assertEquals(1_500_000L, republished.durationMs)
        assertEquals("listening history is kept", 600_000L, republished.positionMs)

        play(db, first.id, 600_000, 1_560_000)
        // A measurement of the previous audio says nothing about a replacement without a declaration.
        assertEquals(0L, refresh(db, server, repo, "https://example.test/audio-v3.mp3", null).durationMs)
        play(db, first.id, 600_000, 1_440_000)
        assertEquals(1_440_000L, refresh(db, server, repo, "https://example.test/audio-v3.mp3", "20:00").durationMs)
    }

    @Test fun `online transcript matching and local transcripts use the measured duration after refresh`() = fixture { db, server, repo ->
        val audio = "https://example.test/audio.mp3"
        val ep = subscribe(db, server, repo, audio, "30:00")
        play(db, ep.id, 600_000, 1_920_000)
        val refreshed = refresh(db, server, repo, audio, "30:00")
        // An unchanged enclosure keeps its local transcript current: no stale marker, same fingerprint and coverage.
        val local = TranscriptEntity(ep.id, "local", refreshed.mediaFingerprint, "tiny", "auto", state = "ready", durationMs = 1_920_000, processedMs = 1_920_000)
        db.library().putTranscript(local); db.library().putCoverage(CoverageEntity(ep.id, "local", 0, 1_920_000))
        val again = refresh(db, server, repo, audio, "30:00")
        assertEquals("ready", db.library().transcript(ep.id)!!.state)
        assertEquals(refreshed.mediaFingerprint, again.mediaFingerprint)
        assertEquals(1_920_000L, db.library().coverage(ep.id, "local").single().endMs)

        // The existing 2 s tolerance is unchanged; its reference is now the audio the player measured.
        val settings = AppSettings(context); settings.flag("onlineTranscripts", true)
        db.sync().approve(SyncPublicFeedEntity(ep.podcastId))
        suspend fun lookup(remoteDurationMs: Long, at: Long): Boolean {
            val row = TranscriptEntity(ep.id, "attempt-$remoteDurationMs", again.mediaFingerprint, "tiny", "auto", state = "processing", lookupOnline = true)
            db.library().putTranscript(row)
            return OnlineTranscriptRepository(db, settings, ExistingTranscriptProvider {
                ExistingTranscript("public-id", audio, remoteDurationMs, "English", "2026-11-05", segments.replace("1920", (remoteDurationMs / 1000).toString()))
            }, now = { at }).lookup(db.library().episode(ep.id)!!, row)
        }
        assertFalse("the feed's declared length no longer matches this audio", lookup(1_800_000, at = 0))
        // A later lookup (after the miss's retry delay) adopts the transcript of the measured audio.
        assertTrue(lookup(1_921_000, at = 86_400_000))
    }
}
