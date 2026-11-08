package dev.distillpod.android

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.android.playback.videocastMediaItem
import dev.distillpod.android.playback.preserveVideoItem
import dev.distillpod.android.video.VideocastMediaSourceFactory
import dev.distillpod.android.video.YouTubeSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideocastMediaSourceTest {
    private val watch = "https://www.youtube.com/watch?v=heoWstJTwu4"
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun factory() = VideocastMediaSourceFactory(context, YouTubeSource { _, _, _ ->
        error("Extraction must wait for the loader thread")
    })

    @Test fun `video timeline preserves the marker and episode identity used by download observers`() {
        val item = MediaItem.Builder().setMediaId("episode").setUri("$watch&distillpod_video=1").build()
        val source = factory().createMediaSource(item)
        assertTrue(source is MergingMediaSource)
        assertEquals(item, source.mediaItem)
        assertEquals("1", source.mediaItem.localConfiguration!!.uri.getQueryParameter("distillpod_video"))
    }

    @Test fun `audio and ordinary RSS items retain their public identity without eager extraction`() {
        for (uri in listOf(watch, "https://example.com/podcast.mp3", "https://example.com/videocast.mp4")) {
            val item = MediaItem.Builder().setMediaId("episode").setUri(uri).build()
            val source = factory().createMediaSource(item)
            assertFalse(source is MergingMediaSource)
            assertEquals(item, source.mediaItem)
        }
    }

    @Test fun `switching a downloaded YouTube episode to video streams and switching back uses offline audio`() {
        val file = File.createTempFile("videocast-", ".audio", context.cacheDir)
        try {
            val ep = EpisodeEntity("episode", "show", "video", "Episode", watch, localFile = file.absolutePath)
            val podcast = PodcastEntity("show", "https://example.com/feed", "Show")
            val audio = videocastMediaItem(ep, podcast, false)
            val video = videocastMediaItem(ep, podcast, true)
            assertEquals(Uri.fromFile(file), audio.localConfiguration!!.uri)
            assertEquals("$watch&distillpod_video=1", video.localConfiguration!!.uri.toString())
            assertEquals(audio.mediaId, video.mediaId)
            assertEquals(audio.mediaMetadata, video.mediaMetadata)
            assertEquals(audio, videocastMediaItem(ep, podcast, false))
            assertEquals(video, preserveVideoItem(ep, podcast, video))
            assertEquals(audio, preserveVideoItem(ep, podcast, video.buildUpon().setMediaId("other").build()))
            assertEquals(audio, preserveVideoItem(ep, podcast, audio))
        } finally { file.delete() }
    }
}
