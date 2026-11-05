package dev.distillpod.android

import android.content.ComponentName
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.distillpod.android.data.*
import dev.distillpod.android.features.AppViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AutomaticTranscriptionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val graph get() = compose.activity.applicationContext.appGraph

    private suspend fun await(check: suspend () -> Boolean) = withTimeout(60000) {
        while (!check()) delay(100)
    }

    private suspend fun setup() {
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic")) { "Use a test emulator" }
        graph.ready.await()
        graph.playback.pause()
        graph.playback.clear()
        await { graph.playback.state.value.queue.isEmpty() }
    }

    private suspend fun restore(prefs: Preferences, podcastId: String) {
        graph.playback.pause()
        graph.playback.clear()
        await { graph.playback.state.value.queue.isEmpty() }
        graph.db.library().episodes().filter { it.podcastId == podcastId }.forEach { graph.transcription.pause(it.id) }
        delay(300)
        graph.db.library().deletePodcast(podcastId)
        graph.settings.flag("transcribeOnPlayback", prefs.transcribeOnPlayback)
        graph.settings.flag("onlineTranscripts", prefs.onlineTranscripts)
        graph.settings.flag("charging", prefs.chargingOnly)
        graph.settings.flag("wifi", prefs.wifiOnly)
        graph.settings.text("model", prefs.model)
        graph.settings.text("language", prefs.language)
    }

    @Test fun playbackQueueSystemControlsAndLiveToggleUseOneTranscriptPerEpisode() = runBlocking<Unit> {
        setup()
        val prefs = graph.settings.flow.first()
        val id = "auto-play-${UUID.randomUUID()}"
        val source = File(compose.activity.cacheDir, "$id.wav")
        val dao = graph.db.library()
        instrumentation.context.assets.open("jfk.wav").use { input -> source.outputStream().use(input::copyTo) }
        val controller = MediaController.Builder(compose.activity, SessionToken(compose.activity,
            ComponentName(compose.activity, PlaybackService::class.java))).buildAsync().get(10, TimeUnit.SECONDS)
        try {
            graph.settings.flag("transcribeOnPlayback", true)
            graph.settings.flag("onlineTranscripts", false)
            graph.settings.flag("charging", false)
            graph.settings.text("model", "base")
            dao.putPodcast(PodcastEntity(id, "https://example.test/$id", "Automatic transcript fixture"))
            for (suffix in listOf("a", "b", "c")) dao.putEpisode(EpisodeEntity("$id-$suffix", id, suffix,
                "Episode $suffix", "https://example.test/$suffix.wav", localFile = source.absolutePath,
                downloadState = "downloaded", downloadedBytes = source.length(), totalBytes = source.length(), mediaFingerprint = id))
            graph.playback.enqueue("$id-a")
            graph.playback.enqueue("$id-b")
            delay(1000)
            assertNull("Queue preparation must not transcribe", dao.transcript("$id-a"))
            assertNull(dao.transcript("$id-b"))
            graph.playback.play("$id-a")
            await { graph.playback.state.value.playing && dao.transcript("$id-a") != null }
            val revision = dao.transcript("$id-a")!!.revision
            instrumentation.runOnMainSync { controller.pause(); controller.seekTo(1000); controller.play() }
            delay(1000)
            assertEquals(revision, dao.transcript("$id-a")!!.revision)
            await { graph.playback.state.value.durationMs > 0 }
            UiDevice.getInstance(instrumentation).pressHome()
            instrumentation.runOnMainSync { controller.seekTo(controller.duration - 1000) }
            await { graph.playback.state.value.episodeId == "$id-b" && graph.playback.state.value.playing && dao.transcript("$id-b") != null }
            graph.transcription.pause("$id-b")
            instrumentation.runOnMainSync { controller.pause(); controller.play() }
            delay(500)
            assertEquals("paused", dao.transcript("$id-b")!!.state)
            graph.settings.flag("transcribeOnPlayback", false)
            graph.playback.play("$id-c")
            await { graph.playback.state.value.episodeId == "$id-c" && graph.playback.state.value.playing }
            delay(500)
            assertNull(dao.transcript("$id-c"))
            graph.settings.flag("transcribeOnPlayback", true)
            await { dao.transcript("$id-c") != null }
            assertNull(graph.playback.state.value.error)
        } finally {
            instrumentation.runOnMainSync { controller.release() }
            restore(prefs, id)
            source.delete()
        }
    }

    @Test fun streamingPlaybackDownloadsAndTranscribesLocallyWithoutReplacingTheActiveRevision() = runBlocking<Unit> {
        setup()
        val prefs = graph.settings.flow.first()
        val id = "auto-stream-${UUID.randomUUID()}"
        val dao = graph.db.library()
        val assets = instrumentation.context.assets
        val bytes = assets.open("jfk.wav").use { it.readBytes() }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse()
                    .setHeader("Content-Type", "audio/wav").setBody(Buffer().write(bytes))
            }
            server.start()
            var downloaded: File? = null
            try {
                val spec = whisperModels.first { it.id == "tiny" }
                val model = File(graph.media.modelsDirectory, "tiny.bin")
                if (!model.exists()) assets.open("ggml-tiny.bin").use { input -> model.outputStream().use(input::copyTo) }
                assertEquals(spec.bytes, model.length())
                dao.putModel(ModelEntity("tiny", "ready", spec.bytes))
                graph.settings.text("model", "tiny")
                graph.settings.text("language", "en")
                graph.settings.flag("transcribeOnPlayback", true)
                graph.settings.flag("onlineTranscripts", false)
                graph.settings.flag("charging", false)
                graph.settings.flag("wifi", false)
                dao.putPodcast(PodcastEntity(id, "https://example.test/$id", "Streaming fixture"))
                dao.putEpisode(EpisodeEntity(id, id, id, "Automatic local transcription", server.url("/audio.wav").toString(), mediaFingerprint = "enclosure:$id"))
                graph.playback.play(id)
                await { dao.transcript(id) != null }
                val revision = dao.transcript(id)!!.revision
                await { dao.transcript(id)?.state in listOf("ready", "failed", "waiting_model") }
                val result = dao.transcript(id)!!
                assertEquals(result.error, "ready", result.state)
                assertEquals("whisper", result.source)
                assertEquals(revision, result.revision)
                assertTrue(dao.segments(id, revision).isNotEmpty())
                val episode = dao.episode(id)!!
                downloaded = episode.localFile?.let(::File)
                assertTrue(downloaded?.isFile == true)
                assertEquals(episode.mediaFingerprint, result.fingerprint)
                assertTrue(graph.playback.state.value.positionMs > 0)
                assertNull(graph.playback.state.value.error)
            } finally {
                downloaded = downloaded ?: dao.episode(id)?.localFile?.let(::File)
                restore(prefs, id)
                downloaded?.delete()
            }
        }
    }

    @Test fun settingsTogglePersistsAndExplainsExistingJobs() = runBlocking<Unit> {
        setup()
        val old = graph.settings.flow.first().transcribeOnPlayback
        try {
            graph.settings.flag("transcribeOnPlayback", true)
            withContext(Dispatchers.Main) {
                ViewModelProvider(compose.activity, AppViewModel.factory(graph))[AppViewModel::class.java].open("settings:transcription")
            }
            compose.onNodeWithText("Transcribe when playing").assertIsDisplayed()
            val toggle = compose.onNodeWithContentDescription("Transcribe when playing")
            toggle.assertIsOn().performClick()
            await { !graph.settings.flow.first().transcribeOnPlayback }
            toggle.assertIsOff()
            compose.onNodeWithText("Turning this off leaves existing jobs running", substring = true).assertIsDisplayed()
        } finally { graph.settings.flag("transcribeOnPlayback", old) }
    }
}
