package dev.distillpod.android

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.video.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in public media measurements on a separate muted player; does not change owner data. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoStartupDeviceTest {
    @Test fun portaDosFundosColdAndWarmStartup() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowPublicYoutube") == "true")
        val context = instrumentation.targetContext
        val extractor = AndroidYouTubeExtractor(context)
        val channel = extractor.extract("https://www.youtube.com/@portadosfundos/videos", true, 3)
        val entries = channel.getJSONArray("entries")
        assertTrue(entries.length() > 0)
        val id = entries.getJSONObject(0).getString("id")
        val url = dev.distillpod.core.YouTubeUrl.watch(id)
        val source = YouTubeSource(extractor)
        val rounds = if (InstrumentationRegistry.getArguments().getString("measureCacheAge") == "true") 3 else 2
        repeat(rounds) { round ->
            if (round == 2) kotlinx.coroutines.delay(130_000)
            val start = android.os.SystemClock.elapsedRealtime()
            source.streams(url)
            val extracted = android.os.SystemClock.elapsedRealtime()
            val frame = CountDownLatch(1)
            val error = AtomicReference<PlaybackException?>()
            var bufferingAt = 0L
            var readyAt = 0L
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
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_BUFFERING && bufferingAt == 0L) bufferingAt = android.os.SystemClock.elapsedRealtime()
                        if (state == Player.STATE_READY && readyAt == 0L) readyAt = android.os.SystemClock.elapsedRealtime()
                    }
                    override fun onRenderedFirstFrame() { frame.countDown() }
                    override fun onPlayerError(e: PlaybackException) { error.set(e); frame.countDown() }
                })
                player.setMediaItem(MediaItem.fromUri(url + "&distillpod_video=1"))
                player.prepare(); player.play()
            }
            try {
                assertTrue("Video must render", frame.await(90, TimeUnit.SECONDS))
                assertNull("Public video decode must succeed", error.get())
                val elapsed = android.os.SystemClock.elapsedRealtime()
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("video_startup", "round=$round extractionMs=${extracted-start} bufferingMs=${(readyAt-bufferingAt).coerceAtLeast(0)} firstFrameMs=${elapsed-start}")
                })
            } finally { instrumentation.runOnMainSync { player.release(); surface.release(); texture.release() } }
        }
    }
}
