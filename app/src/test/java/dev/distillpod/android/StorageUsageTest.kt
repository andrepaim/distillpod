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
    @Test fun completedDeletionFailureAfterPartialRemovalRetriesFromActualDiskOffset() = runBlocking {
        fixture { db, media, _ ->
            MockWebServer().use { server ->
                // Simulate a non-deletable completed target without changing permissions.
                val completed = File(media.directory, "e.audio").apply { mkdirs() }
                val child = File(completed, "fixture").apply { writeText("retained") }
                val partial = File(media.directory, "e.partial").apply { writeText("old") }
                db.library().putEpisode(episode().copy(localFile = completed.absolutePath,
                    audioUrl = server.url("/audio").toString()))
                try {
                    assertTrue(runCatching { media.deleteDownload("e") }.isFailure)
                    assertFalse(partial.exists())
                    assertEquals("retained", child.readText())
                    val unchanged = db.library().episode("e")!!
                    assertEquals("paused", unchanged.downloadState)
                    assertEquals(3L, unchanged.downloadedBytes)
                    assertEquals("old", unchanged.etag)
                    // Physical reporting must not trust the stale resume byte count.
                    assertEquals(0L, media.storageUsage().quotaBytes)
                    server.enqueue(MockResponse().setBody("fresh audio"))
                    assertEquals("fresh audio", media.ensureDownloaded("e").readText())
                    val request = server.takeRequest()
                    assertNull(request.getHeader("Range"))
                    assertNull(request.getHeader("If-Range"))
                    val retried = db.library().episode("e")!!
                    assertEquals("downloaded", retried.downloadState)
                    assertEquals(11L, retried.downloadedBytes)
                    assertEquals(11L, media.storageUsage().downloadedBytes)
                } finally { child.delete(); completed.delete() }
            }
        }
    }
    @Test fun protectedDeletionIsStructuredAndPreservesAllStateUntilOwnershipReleased() = runBlocking {
        fixture { db, media, _ ->
            val file = File(media.directory, "e.audio").apply { writeText("audio") }
            val partial = File(media.directory, "e.partial").apply { writeText("resume") }
            val before = episode().copy(localFile = file.absolutePath, downloadState = "downloaded", positionMs = 42)
            db.library().putEpisode(before)
            db.library().insertQueue(listOf(QueueEntryEntity("e", 0)))
            val rejection = runCatching { media.deleteDownload("e") }.exceptionOrNull()
            assertNotNull(rejection)
            assertEquals("AudioDeletionBlocked", rejection!!::class.java.simpleName)
            assertEquals(before, db.library().episode("e"))
            assertEquals("audio", file.readText())
            assertEquals("resume", partial.readText())
            assertEquals(listOf(QueueEntryEntity("e", 0)), db.library().queue())
            db.library().clearQueue()
            media.deleteDownload("e")
            assertFalse(file.exists())
            assertFalse(partial.exists())
            assertEquals(42L, db.library().episode("e")!!.positionMs)
        }
    }
    @Test fun protectedDeletionShowsActionableFeedbackWithoutUnexpectedFailureReporting() = runBlocking {
        fixture { db, media, _ ->
            val file = File(media.directory, "e.audio").apply { writeText("audio") }
            db.library().putEpisode(episode().copy(localFile = file.absolutePath, downloadState = "downloaded"))
            media.active.playback = file.absolutePath
            val reported = mutableListOf<Exception>()
            val messages = mutableListOf<String>()
            dev.distillpod.android.features.runAppAction(
                { media.deleteDownload("e") }, reported::add, { messages.add(it) })
            assertEquals("Protected deletion must not call the unexpected-error diagnostic sink", emptyList<Exception>(), reported)
            assertEquals(1, messages.size)
            assertTrue(messages.single().contains("Stop"))
            assertEquals("audio", file.readText())
            media.active.playback = null
        }
    }
    @Test fun everyProtectedOwnerKeepsAudioPartialMetadataAndSavedWork() = runBlocking {
        fixture { db, media, _ ->
            val dao = db.library()
            val audio = File(media.directory, "e.audio").apply { writeText("audio") }
            val partial = File(media.directory, "e.partial").apply { writeText("resume") }
            val before = episode().copy(localFile = audio.absolutePath, downloadState = "downloaded",
                positionMs = 1234, lastModified = "resume-date")
            val transcript = TranscriptEntity("e", "r", "fingerprint", "tiny", "en", state = "ready")
            val analysis = AnalysisEntity("e", "r", state = "ready", summary = "Saved insight")
            dao.putEpisode(before); dao.putTranscript(transcript); dao.putAnalysis(analysis)
            suspend fun verify(reason: AudioDeletionBlockReason) {
                val row = dao.episode("e")
                val work = dao.transcript("e")
                val queue = dao.queue()
                val error = runCatching { media.deleteDownload("e") }.exceptionOrNull()
                assertTrue(error is AudioDeletionBlocked)
                assertEquals(reason, (error as AudioDeletionBlocked).reason)
                assertEquals(row, dao.episode("e"))
                assertEquals(work, dao.transcript("e"))
                assertEquals(queue, dao.queue())
                assertEquals(analysis, dao.analysis("e"))
                assertEquals("audio", audio.readText())
                assertEquals("resume", partial.readText())
            }
            media.active.playback = audio.absolutePath
            verify(AudioDeletionBlockReason.ACTIVE)
            media.active.playback = null
            media.active.acquire(audio)
            verify(AudioDeletionBlockReason.ACTIVE)
            media.active.release(audio)
            media.active.acquire(partial)
            verify(AudioDeletionBlockReason.ACTIVE)
            media.active.release(partial)
            media.active.queued = setOf(audio.absolutePath)
            verify(AudioDeletionBlockReason.QUEUED)
            media.active.queued = emptySet()
            dao.insertQueue(listOf(QueueEntryEntity("e", 0)))
            verify(AudioDeletionBlockReason.QUEUED)
            dao.clearQueue()
            for (state in listOf("queued", "processing")) {
                dao.putTranscript(transcript.copy(state = state))
                verify(AudioDeletionBlockReason.TRANSCRIBING)
            }
            dao.putTranscript(transcript)
            for (state in listOf("queued", "downloading")) {
                dao.putEpisode(before.copy(downloadState = state))
                verify(AudioDeletionBlockReason.DOWNLOADING)
            }
            dao.putEpisode(before)
            media.deleteDownload("e")
            assertFalse(audio.exists()); assertFalse(partial.exists())
            assertEquals(transcript, dao.transcript("e"))
            assertEquals(analysis, dao.analysis("e"))
            assertEquals(1234L, dao.episode("e")!!.positionMs)
        }
    }

    @Test fun protectedPausedPartialIsStructuredAndRetriesAfterRelease() = runBlocking {
        fixture { db, media, _ ->
            val before = episode()
            db.library().putEpisode(before)
            val partial = File(media.directory, "e.partial").apply { writeText("old") }
            media.active.acquire(partial)
            val reported = mutableListOf<Exception>()
            val messages = mutableListOf<String>()
            dev.distillpod.android.features.runAppAction(
                { media.discardPausedPartial("e") }, reported::add, { messages.add(it) })
            assertTrue(reported.isEmpty())
            assertEquals(listOf(AudioDeletionBlockReason.ACTIVE.feedback), messages)
            assertEquals(before, db.library().episode("e"))
            assertEquals("old", partial.readText())
            media.active.release(partial)
            assertEquals(3L, media.discardPausedPartial("e"))
            assertFalse(partial.exists())
        }
    }

    @Test fun invalidPathAndFileFailuresStillReachUnexpectedErrorReporting() = runBlocking {
        fixture { db, media, _ ->
            val dao = db.library()
            val outside = File(media.directory.parentFile, "unexpected-audio-fixture").apply { writeText("keep") }
            val partial = File(media.directory, "e.partial").apply { mkdirs() }
            val child = File(partial, "fixture").apply { writeText("keep partial") }
            val reported = mutableListOf<Exception>()
            val messages = mutableListOf<String>()
            try {
                // Even a protected episode with an invalid path must not hide malformed data.
                dao.putEpisode(episode().copy(localFile = outside.absolutePath))
                media.active.playback = outside.absolutePath
                dev.distillpod.android.features.runAppAction(
                    { media.deleteDownload("e") }, reported::add, { messages.add(it) })
                assertTrue(reported.single() is IllegalArgumentException)
                assertEquals("Invalid audio path", messages.single())
                assertEquals("keep", outside.readText())
                media.active.playback = null
                val before = episode()
                dao.putEpisode(before)
                dev.distillpod.android.features.runAppAction(
                    { media.discardPausedPartial("e") }, reported::add, { messages.add(it) })
                assertEquals(2, reported.size)
                assertTrue(reported.last() is IllegalStateException)
                assertEquals(before, dao.episode("e"))
                assertEquals("keep partial", child.readText())
            } finally { outside.delete(); child.delete(); partial.delete() }
        }
    }
    @Test fun resumedPartialBetweenConfirmationAndDiscardIsExpectedAndUnchanged() = runBlocking {
        fixture { db, media, _ ->
            val partial = File(media.directory, "e.partial").apply { writeText("resume") }
            val reported = mutableListOf<Exception>()
            val messages = mutableListOf<String>()
            db.library().putEpisode(episode()) // Confirmation was opened while paused.
            for (state in listOf("queued", "downloading")) {
                val resumed = episode().copy(downloadState = state)
                db.library().putEpisode(resumed)
                dev.distillpod.android.features.runAppAction(
                    { media.discardPausedPartial("e") }, reported::add, { messages.add(it) })
                assertTrue("Resumed work is an expected blocked action", reported.isEmpty())
                assertEquals(AudioDeletionBlockReason.DOWNLOADING.feedback, messages.last())
                assertEquals(resumed, db.library().episode("e"))
                assertEquals("resume", partial.readText())
            }
            db.library().putEpisode(episode())
            assertEquals(6L, media.discardPausedPartial("e"))
            assertFalse(partial.exists())
        }
    }

    @Test fun nonProtectedInvalidPartialStateRemainsUnexpected() = runBlocking {
        fixture { db, media, _ ->
            val partial = File(media.directory, "e.partial").apply { writeText("resume") }
            val before = episode().copy(downloadState = "failed")
            db.library().putEpisode(before)
            val reported = mutableListOf<Exception>()
            dev.distillpod.android.features.runAppAction(
                { media.discardPausedPartial("e") }, reported::add, {})
            assertTrue(reported.single() is IllegalArgumentException)
            assertEquals(before, db.library().episode("e"))
            assertEquals("resume", partial.readText())
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
