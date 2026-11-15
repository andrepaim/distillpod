package dev.distillpod.android

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.ai.AiGateway
import dev.distillpod.android.ai.AiText
import dev.distillpod.android.data.*
import dev.distillpod.android.playback.mediaItem
import dev.distillpod.android.playback.videocastMediaItem
import dev.distillpod.core.episodeId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #124: captures made while streaming the episode's own source are verified against its current identity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureSourceTest {
    private val enclosure = "https://example.test/audio.mp3?token=a"
    private val streamed = EpisodeEntity("e", "p", "g", "Episode", enclosure, durationMs = 200_000,
        mediaFingerprint = episodeId("enclosure", enclosure))
    private val downloaded = streamed.copy(localFile = "/data/audio/e.mp3", downloadState = "downloaded", mediaFingerprint = "sha")
    private val watch = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    private val video = EpisodeEntity("v", "p", "gv", "Video", watch, durationMs = 200_000, localFile = "/data/audio/v.m4a",
        downloadState = "downloaded", mediaFingerprint = "video-sha")

    private fun playing(item: androidx.media3.common.MediaItem) = playingSource(item.localConfiguration?.uri)

    @Test fun downloadedFileIsVerified() {
        assertTrue(capturedFromOwnSource(downloaded, playingSource(Uri.fromFile(java.io.File("/data/audio/e.mp3")))))
    }

    @Test fun streamingTheEpisodesOwnEnclosureIsVerified() {
        assertTrue(capturedFromOwnSource(streamed, playing(mediaItem(streamed, null))))
        // A downloaded episode whose player fell back to the remote enclosure (e.g. no audio-ownership lease).
        assertTrue(capturedFromOwnSource(downloaded, playing(mediaItem(downloaded.copy(localFile = null), null))))
    }

    @Test fun videoModeOfTheEpisodesOwnWatchUrlIsVerified() {
        assertTrue(capturedFromOwnSource(video, playing(videocastMediaItem(video, null, video = true))))
        assertTrue(capturedFromOwnSource(video.copy(localFile = null, mediaFingerprint = episodeId("enclosure", watch)),
            playing(videocastMediaItem(video.copy(localFile = null), null, video = true))))
    }

    @Test fun unknownOrDifferentSourcesStayUnverified() {
        assertFalse(capturedFromOwnSource(streamed, null))
        assertFalse(capturedFromOwnSource(downloaded, "/data/audio/other.mp3"))
        assertFalse(capturedFromOwnSource(streamed, "https://example.test/audio.mp3?token=b"))
        assertFalse(capturedFromOwnSource(streamed, "https://example.test/audio.mp3"))
        // The video marker only identifies the episode's own YouTube source.
        assertFalse(capturedFromOwnSource(streamed, "$enclosure&distillpod_video=1"))
        assertFalse(capturedFromOwnSource(video, "https://www.youtube.com/watch?v=aaaaaaaaaaa&distillpod_video=1"))
        // Without a known identity there is nothing to verify against.
        assertFalse(capturedFromOwnSource(streamed.copy(mediaFingerprint = ""), enclosure))
        assertFalse(capturedFromOwnSource(streamed.copy(audioUrl = ""), ""))
    }

    @Test fun streamedCaptureOfTheTranscribedEnclosureGoesStraightToGeneration() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<android.content.Context>(), AppDatabase::class.java).build()
        try {
            val dao = db.library()
            dao.putPodcast(PodcastEntity("p", "https://example.test/feed", "Show"))
            dao.putEpisode(streamed)
            dao.putTranscript(TranscriptEntity("e", "r", streamed.mediaFingerprint, "publisher", "en", source = "publisher", state = "ready", durationMs = 200_000))
            dao.putCoverage(CoverageEntity("e", "r", 0, 200_000))
            dao.putSegments(listOf(SegmentEntity("e", "r", 40_000, 60_000, "Lower costs can create additional demand.")))
            val enqueued = mutableListOf<String>()
            val repo = CaptureRepository(db, object : AiGateway {
                override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit): AiText = error("unused")
            }, { true }, {}, { enqueued += it })

            val id = repo.capture("e", 60_000, capturedFromOwnSource(streamed, playing(mediaItem(streamed, null))))

            val saved = dao.distillation(id)!!
            assertEquals(streamed.mediaFingerprint, saved.fingerprint)
            assertEquals("queued", saved.state)
            assertEquals(listOf(id), enqueued)
        } finally { db.close() }
    }
}
