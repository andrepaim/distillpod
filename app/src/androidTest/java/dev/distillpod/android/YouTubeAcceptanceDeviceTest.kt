package dev.distillpod.android

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.video.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Explicit public-network acceptance, excluded from ordinary CI and never targets personal data. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class YouTubeAcceptanceDeviceTest {
    @Test fun officialFalhaPlaylistHasPlayableOriginalAudioAndVideo() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowPublicYoutube") == "true")
        check(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.FINGERPRINT.contains("generic")) {
            "YouTube acceptance requires an isolated development emulator"
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = YouTubeSource(AndroidYouTubeExtractor(context))
        val found = source.search("Falha de Cobertura").firstOrNull {
            it.feedUrl == "https://www.youtube.com/playlist?list=PLaE_mZALZ0V3lAEAvIfZC-aKf2ATIY3uy"
        }
        assertNotNull("The actual YouTube search must find Podpah's official series", found)
        val feed = source.feed("isolated-youtube-acceptance", found!!.feedUrl)
        val episode = feed.episodes.first { it.title.contains("Falha de Cobertura", ignoreCase = true) }
        val streams = source.streams(episode.audioUrl)
        assertNotNull("A video stream must be available", streams.video)
        // A directory result or HTTP 200 with an HTML error is not successful acceptance.
        OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url(streams.audio).header("Range", "bytes=0-65535").build()).execute().use {
            assertTrue("Audio request must succeed", it.isSuccessful)
            val type = it.header("Content-Type").orEmpty()
            assertFalse("Audio must not be an HTML error page", type.contains("text/html"))
            val bytes = it.body!!.source().readByteArray(32)
            assertTrue("Audio container must have an MP4 or WebM signature", bytes.size >= 8 &&
                (String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp" || bytes.take(4).map { b -> b.toInt() and 255 } == listOf(0x1a, 0x45, 0xdf, 0xa3)))
        }
        val rendered = CountDownLatch(1)
        val failure = AtomicReference<PlaybackException?>()
        lateinit var player: ExoPlayer
        lateinit var texture: SurfaceTexture
        lateinit var surface: Surface
        instrumentation.runOnMainSync {
            texture = SurfaceTexture(false).apply { setDefaultBufferSize(1280, 720) }
            surface = Surface(texture)
            player = ExoPlayer.Builder(context).setMediaSourceFactory(VideocastMediaSourceFactory(context, source)).build()
            player.volume = 0f
            player.setVideoSurface(surface)
            player.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { rendered.countDown() }
                override fun onPlayerError(error: PlaybackException) { failure.set(error); rendered.countDown() }
            })
            player.setMediaItem(MediaItem.fromUri(episode.audioUrl + "&distillpod_video=1"))
            player.prepare(); player.play()
        }
        try {
            assertTrue("The real video must render a frame", rendered.await(90, TimeUnit.SECONDS))
            assertNull("Playback must not fail", failure.get())
            instrumentation.runOnMainSync {
                assertNotNull("Video must be decoded", player.videoFormat)
                assertNotNull("Audio must be decoded", player.audioFormat)
            }
        } finally { instrumentation.runOnMainSync { player.release(); surface.release(); texture.release() } }
    }
}
