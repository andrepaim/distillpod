package dev.distillpod.android

import android.os.Build
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RetentionAgeDeviceTest {
    @Test fun localClocksFollowCopyIdentityAndRollbackWithTheirRows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.distillpod.android.validation.retention60" ||
            Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic")) { "Use the isolated retention validation app" }
        // No application graph, existing library, actual media files or network is used.
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.library()
            dao.putPodcast(PodcastEntity("p", "https://example.test/feed", "Synthetic age fixture"))
            dao.putEpisode(EpisodeEntity("e", "p", "g", "Episode", "https://example.test/audio"))
            dao.downloadComplete("e", "/synthetic/one", "sha", 1, completedAt = 1000)
            dao.downloadComplete("e", "/synthetic/one", "sha", 1, completedAt = 2000)
            assertEquals(1000L, dao.episode("e")!!.localDownloadedAt)
            val transcript = TranscriptEntity("e", "r", "sha", "tiny", "en")
            dao.putTranscript(transcript, createdAt = 1000)
            dao.putTranscript(transcript.copy(state = "ready"), createdAt = 2000)
            val original = dao.transcript("e")!!
            assertEquals(1000L, original.localCreatedAt)
            val failure = runCatching {
                db.withTransaction {
                    dao.downloadComplete("e", "/synthetic/two", "sha", 1, completedAt = 3000)
                    dao.putTranscript(transcript.copy(revision = "new"), createdAt = 3000)
                    error("Deliberate synthetic rollback")
                }
            }.exceptionOrNull()
            assertEquals("Deliberate synthetic rollback", failure?.message)
            assertEquals(original, dao.transcript("e"))
            assertEquals("/synthetic/one", dao.episode("e")!!.localFile)
            assertEquals(1000L, dao.episode("e")!!.localDownloadedAt)
            dao.downloadComplete("e", "/synthetic/two", "sha", 1, completedAt = 4000)
            dao.putTranscript(transcript.copy(revision = "new"), createdAt = 4000)
            assertEquals(4000L, dao.episode("e")!!.localDownloadedAt)
            assertEquals(4000L, dao.transcript("e")!!.localCreatedAt)
            dao.removeDownload("e")
            assertEquals(0L, dao.episode("e")!!.localDownloadedAt)
            assertEquals(4000L, dao.transcript("e")!!.localCreatedAt)
        } finally { db.close() }
    }
}
