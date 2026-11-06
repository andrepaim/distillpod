package dev.distillpod.android

import android.content.ComponentName
import android.media.browse.MediaBrowser as PlatformBrowser
import android.media.session.MediaController as PlatformController
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.distillpod.android.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Real service/platform-key dispatch, with synthetic audio and no account requests. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HeadphoneDistillDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun <T> main(block: () -> T): T {
        val result = CompletableFuture<T>()
        instrumentation.runOnMainSync {
            try { result.complete(block()) } catch (e: Throwable) { result.completeExceptionally(e) }
        }
        return result.get(15, TimeUnit.SECONDS)
    }
    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (check()) return
            Thread.sleep(50)
        }
        fail("Expected playback or capture state was not reached")
    }
    @Test fun platformPreviousKeySavesOnceWithoutSkippingAndToggleRestoresNormalBehavior() {
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic")) { "Use a test emulator" }
        val graph = context.appGraph
        val prefs = runBlocking { graph.ready.await(); graph.settings.flow.first() }
        val id = "headphone-${UUID.randomUUID()}"
        val file = File(context.cacheDir, "$id.wav")
        val bytes = ByteBuffer.allocate(44 + 16000 * 2 * 180).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(bytes.capacity() - 8).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(bytes.capacity() - 44)
        file.writeBytes(bytes.array())
        val dao = graph.db.library()
        val component = ComponentName(context, PlaybackService::class.java)
        var modern: MediaController? = null
        var legacy: PlatformBrowser? = null
        try {
            runBlocking {
                graph.settings.flag("headphoneDistill", true)
                graph.settings.flag("transcribeOnPlayback", false)
                graph.settings.flag("distillSound", false)
                dao.putPodcast(PodcastEntity(id, "https://example.invalid/$id", "Headphone test"))
                for (suffix in listOf("a", "b")) {
                    val episodeId = "$id-$suffix"
                    dao.putEpisode(EpisodeEntity(episodeId, id, suffix, "Synthetic episode", "https://example.invalid/$suffix",
                        durationMs = 180000, localFile = file.path, downloadState = "downloaded", mediaFingerprint = id))
                    // Silence with complete coverage prevents capture from requesting AI or transcription.
                    dao.putTranscript(TranscriptEntity(episodeId, id, id, "publisher", "en", source = "publisher",
                        state = "ready", durationMs = 180000, processedMs = 180000))
                    dao.putCoverage(CoverageEntity(episodeId, id, 0, 180000))
                }
            }
            val future = main { MediaController.Builder(context, SessionToken(context, component)).buildAsync() }
            val controller = future.get(15, TimeUnit.SECONDS).also { modern = it }
            val connected = CompletableFuture<PlatformController>()
            main {
                val browser = PlatformBrowser(context, component, object : PlatformBrowser.ConnectionCallback() {
                    override fun onConnected() { connected.complete(PlatformController(context, legacy!!.sessionToken)) }
                    override fun onConnectionFailed() { connected.completeExceptionally(IllegalStateException("Legacy connection failed")) }
                }, null)
                legacy = browser; browser.connect()
                controller.setMediaItems(listOf("a", "b").map {
                    MediaItem.Builder().setMediaId("$id-$it").setUri(android.net.Uri.fromFile(file)).build()
                }, 0, 75000)
                controller.prepare(); controller.play()
            }
            val platform = connected.get(15, TimeUnit.SECONDS)
            await { main { controller.isPlaying && controller.currentPosition >= 75000 } }
            assertEquals(2, main { controller.mediaItemCount })
            fun key(code: Int, repeats: Boolean = false) {
                val time = SystemClock.uptimeMillis()
                platform.dispatchMediaButtonEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0))
                if (repeats) platform.dispatchMediaButtonEvent(KeyEvent(time, time + 1, KeyEvent.ACTION_DOWN, code, 1))
                platform.dispatchMediaButtonEvent(KeyEvent(time, time + 2, KeyEvent.ACTION_UP, code, 0))
            }
            key(KeyEvent.KEYCODE_MEDIA_PREVIOUS, repeats = true)
            await { runBlocking { dao.distillations().count { it.episodeId == "$id-a" } == 1 } }
            Thread.sleep(900)
            val saved = runBlocking { dao.distillations().filter { it.episodeId == "$id-a" } }.single()
            assertTrue(saved.endMs in 75000..85000)
            assertEquals(saved.endMs - 60000, saved.startMs)
            assertEquals(id, saved.fingerprint)
            assertEquals("$id-a", main { controller.currentMediaItem!!.mediaId })
            assertEquals(2, main { controller.mediaItemCount })
            assertTrue(main { controller.isPlaying })
            // Exercise Android's system route as well as an app's platform controller.
            main { controller.seekTo(105000) }
            await { main { controller.currentPosition >= 105000 } }
            val device = UiDevice.getInstance(instrumentation)
            val wasScreenOn = device.isScreenOn
            try {
                device.sleep()
                assertFalse(device.isScreenOn)
                instrumentation.uiAutomation.executeShellCommand("input keyevent 88").use { descriptor ->
                    FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
                }
                await { runBlocking { dao.distillations().count { it.episodeId == "$id-a" } == 2 } }
            } finally {
                if (wasScreenOn) device.wakeUp()
            }
            assertEquals("$id-a", main { controller.currentMediaItem!!.mediaId })
            assertTrue(main { controller.isPlaying })
            // Direct modern transport commands are independent from the key remapping.
            main { controller.seekToNextMediaItem() }
            await { main { controller.currentMediaItem?.mediaId == "$id-b" && controller.playbackState == Player.STATE_READY } }
            key(KeyEvent.KEYCODE_MEDIA_PAUSE)
            await { main { !controller.playWhenReady } }
            main { controller.seekTo(75000) }
            await { main { controller.currentPosition >= 75000 } }
            runBlocking { graph.settings.flag("headphoneDistill", false) }
            // Allow the service's DataStore collector to observe the write before sending a new press.
            Thread.sleep(500)
            key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            Thread.sleep(500)
            assertTrue(runBlocking { dao.distillations().none { it.episodeId == "$id-b" } })
            // The existing notification session may omit legacy skip commands; modern UI commands remain usable.
            main { controller.seekToPrevious() }
            await { main { controller.currentPosition < 1000 || controller.currentMediaItem?.mediaId == "$id-a" } }
        } finally {
            main { modern?.let { it.stop(); it.clearMediaItems(); it.release() }; legacy?.disconnect() }
            runBlocking {
                graph.settings.flag("headphoneDistill", prefs.headphoneDistill)
                graph.settings.flag("transcribeOnPlayback", prefs.transcribeOnPlayback)
                graph.settings.flag("distillSound", prefs.distillSound)
                dao.deletePodcast(id)
            }
            file.delete()
        }
    }
}
