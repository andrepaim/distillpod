package dev.distillpod.android

import android.content.ComponentName
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.*
import dev.distillpod.android.playback.mediaItem
import dev.distillpod.android.widget.NowPlayingWidget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** #189: widget taps reach the real session through the app's PendingIntents. Synthetic audio, no account requests. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NowPlayingWidgetDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun <T> main(block: () -> T): T {
        val result = CompletableFuture<T>()
        instrumentation.runOnMainSync { try { result.complete(block()) } catch (e: Throwable) { result.completeExceptionally(e) } }
        return result.get(15, TimeUnit.SECONDS)
    }
    private fun await(what: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) { if (check()) return; Thread.sleep(50) }
        fail("Timed out waiting for $what")
    }

    @Test fun widgetActionsDriveTheSessionAndHonourTheCaptureRule() {
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic")) { "Use a test emulator" }
        val graph = context.appGraph
        val prefs = runBlocking { graph.ready.await(); graph.settings.flow.first() }
        val id = "widget-${UUID.randomUUID()}"
        val episodeId = "$id-a"
        val file = File(context.cacheDir, "$id.wav")
        val bytes = ByteBuffer.allocate(44 + 16000 * 2 * 180).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(bytes.capacity() - 8).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(bytes.capacity() - 44)
        file.writeBytes(bytes.array())
        val dao = graph.db.library()
        var controller: MediaController? = null
        fun moments() = runBlocking { dao.distillations().filter { it.episodeId == episodeId } }
        try {
            runBlocking {
                graph.settings.flag("transcribeOnPlayback", false)
                graph.settings.flag("distillSound", false)
                dao.putPodcast(PodcastEntity(id, "https://example.invalid/$id", "Widget show"))
                dao.putEpisode(EpisodeEntity(episodeId, id, "a", "Widget episode", "https://example.invalid/a",
                    durationMs = 180000, localFile = file.path, downloadState = "downloaded", mediaFingerprint = id))
                // Silence with complete coverage keeps capture local: no AI or transcription request.
                dao.putTranscript(TranscriptEntity(episodeId, id, id, "publisher", "en", source = "publisher",
                    state = "ready", durationMs = 180000, processedMs = 180000))
                dao.putCoverage(CoverageEntity(episodeId, id, 0, 180000))
            }
            val c = main { MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
                .get(15, TimeUnit.SECONDS).also { controller = it }
            main {
                c.setMediaItems(listOf(mediaItem(runBlocking { dao.episode(episodeId)!! }, runBlocking { dao.podcast(id) })), 0, 75000)
                c.prepare(); c.play()
            }
            await("playing state on the widget") { NowPlayingWidget.live?.let { it.playing && it.title == "Widget episode" && it.show == "Widget show" } == true }
            assertTrue(NowPlayingWidget.live!!.captureAvailable)

            NowPlayingWidget.actionIntent(context, NowPlayingWidget.ACTION_DISTILL).send()
            await("a moment saved from the widget") { moments().size == 1 }
            val saved = moments().single()
            assertTrue("Saved at ${saved.endMs}", saved.endMs in 75000..90000)
            assertEquals(saved.endMs - 60000, saved.startMs)
            assertTrue("Capture does not interrupt playback", main { c.isPlaying })

            NowPlayingWidget.actionIntent(context, NowPlayingWidget.ACTION_TOGGLE).send()
            await("pause from the widget") { !main { c.playWhenReady } && NowPlayingWidget.live?.playing == false }
            NowPlayingWidget.actionIntent(context, NowPlayingWidget.ACTION_TOGGLE).send()
            await("resume from the widget") { main { c.isPlaying } && NowPlayingWidget.live?.playing == true }

            // At 0:00 there is nothing to distill: the widget and the receiver both refuse, as Now Playing does (#146).
            main { c.pause(); c.seekTo(0) }
            await("capture unavailable at 0:00") { NowPlayingWidget.live?.captureAvailable == false }
            NowPlayingWidget.actionIntent(context, NowPlayingWidget.ACTION_DISTILL).send()
            Thread.sleep(1500)
            assertEquals(1, moments().size)
        } finally {
            main { controller?.let { it.stop(); it.clearMediaItems(); it.release() } }
            runBlocking {
                graph.settings.flag("transcribeOnPlayback", prefs.transcribeOnPlayback)
                graph.settings.flag("distillSound", prefs.distillSound)
                dao.deletePodcast(id)
            }
            file.delete()
        }
    }
}
