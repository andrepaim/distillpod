package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import java.io.File
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
class StorageUsageTest {
    @Test fun actualFilesIncludePartialsOrphansAndModelsWithoutTrustingDatabaseSizes() = runBlocking {
        fixture { db, media, _ ->
            val file = File(media.directory, "complete.audio").apply { writeBytes(ByteArray(11)) }
            db.library().putEpisode(episode().copy(localFile = file.absolutePath, downloadState = "downloaded", downloadedBytes = 999))
            File(media.directory, "e.partial").writeBytes(ByteArray(7))
            File(media.directory, "orphan.audio").writeBytes(ByteArray(5))
            File(media.modelsDirectory, "tiny.partial").writeBytes(ByteArray(13))
            val usage = media.storageUsage()
            assertEquals(23L, usage.quotaBytes)
            assertEquals(11L, usage.downloadedBytes)
            assertEquals(12L, usage.partialCacheBytes)
            assertEquals(13L, usage.modelBytes)
        }
    }
    @Test fun partialOnlyUsageAndLoweredLimitNeverDeleteFiles() = runBlocking {
        fixture { _, media, settings ->
            val partial = File(media.directory, "e.partial").apply { java.io.RandomAccessFile(this, "rw").use { it.setLength(300_000_000) } }
            settings.storage(250_000_000)
            assertEquals(300_000_000L, media.storageUsage().quotaBytes)
            assertEquals(300_000_000L, media.storageUsage().partialCacheBytes)
            assertTrue(partial.exists())
        }
    }
    @Test fun selectedPausedPartialIsDiscardedAndRetryStartsFresh() = runBlocking {
        fixture { db, media, _ ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("fresh audio"))
                db.library().putEpisode(episode().copy(audioUrl = server.url("/audio").toString(), positionMs = 1234))
                val partial = File(media.directory, "e.partial").apply { writeText("old") }
                media.discardPausedPartial("e")
                assertFalse(partial.exists())
                val reset = db.library().episode("e")!!
                assertEquals(0L, reset.downloadedBytes)
                assertNull(reset.etag)
                assertEquals(1234L, reset.positionMs)
                assertEquals("fresh audio", media.ensureDownloaded("e").readText())
                assertNull(server.takeRequest().getHeader("Range"))
            }
        }
    }
    @Test fun pausedCleanupRechecksQueuedPlaybackAndProcessingProtections() = runBlocking {
        fixture { db, media, _ ->
            db.library().putEpisode(episode())
            val partial = File(media.directory, "e.partial").apply { writeText("keep") }
            media.active.playback = partial.absolutePath
            assertTrue(runCatching { media.discardPausedPartial("e") }.isFailure)
            media.active.playback = null
            media.active.acquire(partial)
            assertTrue(runCatching { media.discardPausedPartial("e") }.isFailure)
            media.active.release(partial)
            db.library().insertQueue(listOf(QueueEntryEntity("e", 0)))
            assertTrue(runCatching { media.discardPausedPartial("e") }.isFailure)
            db.library().clearQueue()
            db.library().putEpisode(episode().copy(downloadState = "queued"))
            assertTrue(runCatching { media.discardPausedPartial("e") }.isFailure)
            assertTrue(partial.exists())
            db.library().putEpisode(episode())
            db.library().putTranscript(TranscriptEntity("e", "r", "", "tiny", "en", state = "processing"))
            assertTrue(runCatching { media.discardPausedPartial("e") }.isFailure)
            assertTrue(partial.exists())
        }
    }
    @Test fun cacheCleanupKeepsDatabaseQueuedPartialsAndReportsFreedSpace() = runBlocking {
        fixture { db, media, _ ->
            db.library().putEpisode(episode().copy(downloadState = "failed"))
            db.library().insertQueue(listOf(QueueEntryEntity("e", 0)))
            val queued = File(media.directory, "e.partial").apply { writeText("keep") }
            File(media.directory, "orphan.audio").writeText("remove")
            assertEquals(10L, media.storageUsage().quotaBytes)
            assertEquals(6L, media.clearUnusedAudio())
            assertTrue(queued.exists())
            assertEquals(4L, media.storageUsage().quotaBytes)
        }
    }
    @Test fun completedDownloadManagementKeepsQueuedAudioAndListeningState() = runBlocking {
        fixture { db, media, _ ->
            val file = File(media.directory, "e.audio").apply { writeText("audio") }
            db.library().putEpisode(episode().copy(localFile = file.absolutePath, downloadState = "downloaded", positionMs = 42))
            db.library().insertQueue(listOf(QueueEntryEntity("e", 0)))
            assertTrue(runCatching { media.deleteDownload("e") }.isFailure)
            assertTrue(file.exists())
            db.library().clearQueue()
            db.library().putPlayerSession(PlayerSessionEntity(episodeId = "e", speed = 1.5f))
            media.deleteDownload("e")
            assertFalse(file.exists())
            assertEquals(42L, db.library().episode("e")!!.positionMs)
            assertNull(db.library().episode("e")!!.localFile)
            assertEquals("e", db.library().playerSession()!!.episodeId)
            assertEquals(1.5f, db.library().playerSession()!!.speed)
        }
    }
    @Test fun orphanUsageEnforcesQuotaAndCleanupAllowsRetry() = runBlocking {
        fixture { db, media, settings ->
            MockWebServer().use { server ->
                settings.storage(250_000_000)
                val orphan = File(media.directory, "orphan.audio").apply {
                    java.io.RandomAccessFile(this, "rw").use { it.setLength(250_000_000) }
                }
                db.library().putEpisode(episode().copy(audioUrl = server.url("/audio").toString(), downloadState = "none"))
                server.enqueue(MockResponse().setBody("audio"))
                assertTrue(runCatching { media.ensureDownloaded("e") }.isFailure)
                assertEquals(250_000_000L, media.storageUsage().quotaBytes)
                assertTrue(orphan.exists())
                assertEquals(250_000_000L, media.clearUnusedAudio())
                server.enqueue(MockResponse().setBody("audio"))
                assertEquals("audio", media.ensureDownloaded("e").readText())
                assertEquals(5L, media.storageUsage().downloadedBytes)
            }
        }
    }
    @Test fun failedPartialDeletionKeepsResumeMetadata() = runBlocking {
        fixture { db, media, _ ->
            db.library().putEpisode(episode())
            // A non-empty synthetic directory forces File.delete() to fail without permissions changes.
            val partial = File(media.directory, "e.partial").apply { mkdirs() }
            val child = File(partial, "fixture").apply { writeText("keep") }
            try {
                assertTrue(runCatching { media.discardPausedPartial("e") }.isFailure)
                val unchanged = db.library().episode("e")!!
                assertEquals("paused", unchanged.downloadState)
                assertEquals(3L, unchanged.downloadedBytes)
                assertEquals("old", unchanged.etag)
                assertEquals("keep", child.readText())
            } finally { child.delete(); partial.delete() }
        }
    }
    @Test fun partialCleanupRejectsPathsOutsideMediaDirectory() = runBlocking {
        fixture { db, media, _ ->
            val id = "../storage-outside-fixture"
            db.library().putEpisode(episode().copy(id = id))
            val outside = File(media.directory.parentFile, "storage-outside-fixture.partial").apply { writeText("keep") }
            try {
                assertTrue(runCatching { media.discardPausedPartial(id) }.isFailure)
                assertEquals("keep", outside.readText())
                assertEquals("paused", db.library().episode(id)!!.downloadState)
            } finally { outside.delete() }
        }
    }
    @Test fun failedPartialCleanupDoesNotDeleteCompletedAudio() = runBlocking {
        fixture { db, media, _ ->
            val audio = File(media.directory, "e.audio").apply { writeText("completed audio") }
            val partial = File(media.directory, "e.partial").apply { mkdirs() }
            val child = File(partial, "fixture").apply { writeText("keep") }
            db.library().putEpisode(episode().copy(localFile = audio.absolutePath, downloadState = "downloaded"))
            try {
                assertTrue(runCatching { media.deleteDownload("e") }.isFailure)
                assertTrue("Completed audio must survive a failed partial cleanup", audio.exists())
                assertEquals(audio.absolutePath, db.library().episode("e")!!.localFile)
            } finally { child.delete(); partial.delete() }
        }
    }
    private fun episode() = EpisodeEntity("e", "p", "g", "Episode", "https://example.test/audio",
        downloadState = "paused", downloadedBytes = 3, etag = "old")
    private suspend fun fixture(block: suspend (AppDatabase, MediaRepository, AppSettings) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val settings = AppSettings(context)
        val media = MediaRepository(context, db, settings, ResumableDownloader(OkHttpClient()), ActiveFiles())
        // Robolectric's disposable application storage only. Never device storage.
        media.directory.listFiles()?.forEach { it.delete() }
        media.modelsDirectory.listFiles()?.forEach { it.delete() }
        settings.storage(1_000_000_000)
        try { db.library().putPodcast(PodcastEntity("p", "https://example.test/feed", "Show")); block(db, media, settings) }
        finally { db.close() }
    }
}
