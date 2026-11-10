package dev.distillpod.android

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import dev.distillpod.android.data.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*

/** Uses real repository operations with only synthetic files and an in-memory library. */
internal object TranscriptionAudioOwnershipFixture {
    fun protectsPausedReader(context: Context) = fixture(context) { media, dao, episode, audio ->
        val other = File(media.directory, "other.audio").apply { writeText("other") }
        dao.putEpisode(episode.copy(id = "other", guid = "other", localFile = other.absolutePath, downloadedBytes = 5))
        val paused = checkNotNull(dao.transcript(episode.id))
        media.withDownloadedAudio(episode.id) { selected ->
            assertEquals(audio, selected)
            val failure = runCatching { media.deleteDownload(episode.id) }.exceptionOrNull()
            assertEquals(AudioDeletionBlockReason.ACTIVE, (failure as? AudioDeletionBlocked)?.reason)
            assertEquals(episode, dao.episode(episode.id))
            assertEquals(paused, dao.transcript(episode.id))
            assertEquals("sample", selected.readText())
            media.deleteDownload("other")
            assertFalse(other.exists())
        }
        media.deleteDownload(episode.id)
        assertFalse(audio.exists())
        assertEquals(0L, dao.episode(episode.id)?.localDownloadedAt)
        assertEquals(episode.positionMs, dao.episode(episode.id)?.positionMs)
        assertEquals(paused, dao.transcript(episode.id))
    }

    fun protectsSelectedOrphan(context: Context) = fixture(context) { media, dao, episode, audio ->
        media.withDownloadedAudio(episode.id) { selected ->
            // A refresh or a replacement may detach this copy from the episode.
            dao.removeDownload(episode.id)
            assertEquals(0L, media.clearUnusedAudio())
            assertEquals("sample", selected.readText())
        }
        assertEquals(6L, media.clearUnusedAudio())
        assertFalse(audio.exists())
    }

    fun nestedCancellationReleasesOnlyItsOwnReader(context: Context) = fixture(context) { media, _, episode, audio ->
        media.withDownloadedAudio(episode.id) {
            coroutineScope {
                val started = CompletableDeferred<Unit>()
                val reader = launch {
                    media.withDownloadedAudio(episode.id) {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
                try { started.await() } finally { reader.cancelAndJoin() }
            }
            val failure = runCatching { media.deleteDownload(episode.id) }.exceptionOrNull()
            assertEquals(AudioDeletionBlockReason.ACTIVE, (failure as? AudioDeletionBlocked)?.reason)
            assertEquals("sample", audio.readText())
        }
        media.deleteDownload(episode.id)
        assertFalse(audio.exists())
    }

    fun failureIsPreservedAndReleasesReader(context: Context) = fixture(context) { media, _, episode, audio ->
        val expected = IOException("Synthetic reader failure")
        val failure = runCatching {
            media.withDownloadedAudio(episode.id) {
                assertEquals("sample", it.readText())
                throw expected
            }
        }.exceptionOrNull()
        assertSame(expected, failure)
        media.deleteDownload(episode.id)
        assertFalse(audio.exists())
    }

    private fun fixture(
        context: Context,
        block: suspend (MediaRepository, LibraryDao, EpisodeEntity, File) -> Unit,
    ) = runBlocking<Unit> {
        val directory = File(context.cacheDir, "audio-ownership-" + UUID.randomUUID())
        check(directory.mkdir())
        val synthetic = object : ContextWrapper(context) {
            override fun getFilesDir(): File = directory
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.library()
            val media = MediaRepository(synthetic, db, AppSettings(context), ResumableDownloader(OkHttpClient()), ActiveFiles())
            val audio = File(media.directory, "fixture.audio").apply { writeText("sample") }
            val episode = EpisodeEntity("episode", "show", "guid", "Synthetic episode", "https://example.test/audio",
                localFile = audio.absolutePath, downloadState = "downloaded", downloadedBytes = 6,
                positionMs = 1234, localDownloadedAt = 5678)
            dao.putPodcast(PodcastEntity("show", "https://example.test/feed", "Synthetic show"))
            dao.putEpisode(episode)
            dao.putTranscript(TranscriptEntity(episode.id, "revision", "fingerprint", "tiny", "en",
                state = "paused", processedMs = 4321), createdAt = 2468)
            withTimeout(10000) { block(media, dao, episode, audio) }
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }
}
