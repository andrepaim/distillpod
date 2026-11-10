package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import dev.distillpod.core.RetentionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionAgeTest {
    private fun fixture(block: suspend (LibraryDao) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val dao = db.library()
            dao.putPodcast(PodcastEntity("p", "https://example.test/feed", "Show"))
            dao.putEpisode(EpisodeEntity("e", "p", "g", "Episode", "https://example.test/audio", publishedAt = 1, lastPlayedAt = 2))
            block(dao)
        } finally { db.close() }
    }

    @Test fun `download ages belong to the local copy and reset on removal`() = fixture { dao ->
        assertEquals(0L, dao.episode("e")!!.localDownloadedAt)
        dao.downloadComplete("e", "/synthetic/first.audio", "sha", 10, completedAt = 1000)
        assertEquals(1000L, dao.episode("e")!!.localDownloadedAt)
        dao.downloadComplete("e", "/synthetic/first.audio", "sha", 10, completedAt = 2000)
        assertEquals(1000L, dao.episode("e")!!.localDownloadedAt)
        dao.downloadComplete("e", "/synthetic/second.audio", "sha", 10, completedAt = 3000)
        assertEquals(3000L, dao.episode("e")!!.localDownloadedAt)
        dao.removeDownload("e")
        assertEquals(0L, dao.episode("e")!!.localDownloadedAt)
        assertEquals(1L, dao.episode("e")!!.publishedAt)
        assertEquals(2L, dao.episode("e")!!.lastPlayedAt)
    }

    @Test fun `ordinary progress and metadata updates do not postpone local age`() = fixture { dao ->
        dao.downloadComplete("e", "/synthetic/first.audio", "sha", 10, completedAt = 1000)
        dao.progress("e", 20, 100, true, 5000)
        dao.markCompleted("e", false)
        dao.putEpisode(dao.episode("e")!!.copy(title = "Updated feed metadata"))
        assertEquals(1000L, dao.episode("e")!!.localDownloadedAt)
    }

    @Test fun `transcript retry progress and imports preserve revision origin`() = fixture { dao ->
        val row = TranscriptEntity("e", "r", "sha", "tiny", "en")
        dao.putTranscript(row.copy(localCreatedAt = 1), createdAt = 1000)
        dao.putTranscript(row.copy(state = "processing", processedMs = 50), createdAt = 2000)
        dao.putTranscript(row.copy(state = "ready", localCreatedAt = 9000), createdAt = 3000)
        assertEquals(1000L, dao.transcript("e")!!.localCreatedAt)
        assertEquals("ready", dao.transcript("e")!!.state)
        dao.putTranscript(row.copy(revision = "new", localCreatedAt = 1), createdAt = 4000)
        assertEquals(4000L, dao.transcript("e")!!.localCreatedAt)
    }

    @Test fun `unknown legacy age remains unknown across progress until explicit future grace handling`() = fixture { dao ->
        val row = TranscriptEntity("e", "r", "sha", "tiny", "en")
        dao.putTranscript(row, createdAt = 0)
        dao.putTranscript(row.copy(state = "ready"), createdAt = 9000)
        assertEquals(0L, dao.transcript("e")!!.localCreatedAt)
        assertFalse(RetentionPolicy(transcriptDays = 1).transcriptExpired(dao.transcript("e")!!.localCreatedAt, Long.MAX_VALUE))
        dao.putEpisode(dao.episode("e")!!.copy(localFile = "/synthetic/legacy.audio", downloadState = "downloaded"))
        dao.downloadComplete("e", "/synthetic/legacy.audio", "sha", 10, completedAt = 9000)
        assertEquals(0L, dao.episode("e")!!.localDownloadedAt)
    }

    @Test fun `invalid clocks fail closed rather than producing eligible ancient content`() = fixture { dao ->
        dao.downloadComplete("e", "/synthetic/first.audio", "sha", 10, completedAt = -1)
        dao.putTranscript(TranscriptEntity("e", "r", "sha", "tiny", "en"), createdAt = -1)
        assertEquals(0L, dao.episode("e")!!.localDownloadedAt)
        assertEquals(0L, dao.transcript("e")!!.localCreatedAt)
        assertFalse(RetentionPolicy(audioDays = 1).audioExpired(dao.episode("e")!!.localDownloadedAt, null, Long.MAX_VALUE))
    }
}
