package dev.distillpod.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.room.Room
import dev.distillpod.android.data.*
import java.io.File
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptionAudioOwnershipTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun protectsPausedReader() = TranscriptionAudioOwnershipFixture.protectsPausedReader(context)
    @Test fun protectsSelectedOrphan() = TranscriptionAudioOwnershipFixture.protectsSelectedOrphan(context)
    @Test fun nestedCancellationReleasesOnlyItsOwnReader() =
        TranscriptionAudioOwnershipFixture.nestedCancellationReleasesOnlyItsOwnReader(context)
    @Test fun failureIsPreservedAndReleasesReader() = TranscriptionAudioOwnershipFixture.failureIsPreservedAndReleasesReader(context)

    @Test fun aliasedDetachedReaderOwnsTheCanonicalOrphanUntilItUnwinds() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val media = MediaRepository(context, db, AppSettings(context), ResumableDownloader(OkHttpClient()), ActiveFiles())
        val file = File(media.directory, "alias-reader.audio").apply { writeText("reader") }
        val alias = File(media.directory, "../media/alias-reader.audio")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            db.library().putPodcast(PodcastEntity("alias-show", "https://example.test/feed", "Show"))
            db.library().putEpisode(EpisodeEntity("alias-reader", "alias-show", "guid", "Episode", "https://example.test/audio",
                localFile = alias.absolutePath, downloadState = "downloaded", downloadedBytes = file.length()))
            val reader = async { media.withDownloadedAudio("alias-reader") { source ->
                assertEquals(alias.absolutePath, source.absolutePath)
                entered.complete(Unit); release.await()
            } }
            entered.await()
            db.library().removeDownload("alias-reader")
            assertEquals("A detached alias is still being read", 0L, media.clearUnusedAudio())
            assertTrue(file.isFile)
            release.complete(Unit); reader.await()
            assertEquals(6L, media.clearUnusedAudio())
        } finally { release.complete(Unit); file.delete(); db.close() }
    }
}
