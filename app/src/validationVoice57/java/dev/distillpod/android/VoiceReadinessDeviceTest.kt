package dev.distillpod.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.*
import dev.distillpod.android.features.VoiceSessionContent
import dev.distillpod.android.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Synthetic offline evidence only. No real credentials, provider, microphone or owner app data. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VoiceReadinessDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun guard() {
        check(compose.activity.packageName == "dev.distillpod.android.validation.voice57") { "Use an isolated validation app" }
    }
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) { if (check()) return; Thread.sleep(50) }
        fail("Synthetic voice readiness condition was not reached")
    }
    @Test fun feedbackAndRetryRemainVisibleWithoutMicrophone() {
        guard()
        var state by mutableStateOf(VoiceState(error = VoiceReadiness.MISSING_TRANSCRIPT.feedback))
        var retries = 0
        compose.runOnUiThread { compose.activity.setContentForReadiness { VoiceSessionContent(state, false, {}, {}, { retries++ }) } }
        for (reason in VoiceReadiness.entries) {
            compose.runOnIdle { state = VoiceState(error = reason.feedback) }
            compose.onNodeWithText(reason.feedback).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Try again").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Microphone input level").assertDoesNotExist()
        }
        assertEquals(VoiceReadiness.entries.size, retries)
        compose.runOnIdle { state = VoiceState(answer = "Synthetic successful retry") }
        compose.onNodeWithText("Last answer").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }
    @Test fun playbackPreflightRaceRetryAndLeaseRevocation() = runBlocking {
        guard()
        val context = compose.activity.applicationContext
        val graph = context.appGraph
        graph.ready.await()
        val originalPreferences = graph.settings.flow.first()
        graph.settings.flag("transcribeOnPlayback", false)
        await { !graph.preferences.value.transcribeOnPlayback }
        check(!graph.voiceApiKey.hasKey()) { "Use an isolated app without real credentials" }
        val id = "voice-readiness-${UUID.randomUUID()}"
        val file = File(context.cacheDir, "$id.wav")
        val bytes = ByteBuffer.allocate(44 + 16000 * 2 * 120).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(bytes.capacity() - 8).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(bytes.capacity() - 44)
        file.writeBytes(bytes.array())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var player: MediaController? = null
        var coordinator: VoiceCoordinator? = null
        val dao = graph.db.library()
        try {
            dao.putPodcast(PodcastEntity(id, "https://example.invalid/$id", "Synthetic voice"))
            val episode = EpisodeEntity(id, id, id, "Synthetic voice", "https://example.invalid/audio", durationMs = 120000,
                localFile = file.path, downloadState = "downloaded", mediaFingerprint = "fixture")
            dao.putEpisode(episode)
            val future = main { MediaController.Builder(context, SessionToken(context,
                android.content.ComponentName(context, PlaybackService::class.java))).buildAsync() }
            val c = future.get(15, TimeUnit.SECONDS).also { player = it }
            main {
                c.setMediaItem(MediaItem.Builder().setMediaId(id).setUri(android.net.Uri.fromFile(file)).build(), 60000)
                c.prepare(); c.play()
            }
            await { main { c.isPlaying } }
            var pausedEvents = 0
            val listener = object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (!playWhenReady) pausedEvents++ }
            }
            main { c.addListener(listener) }
            var preflight = true; var race = false; var connections = 0; var restores = 0
            var captured = 0L
            val voice = VoiceCoordinator(scope, { changed ->
                val lease = graph.playback.pauseForVoice(changed, 3000)
                captured = lease.position
                object : VoiceLease {
                    override val episode = lease.episode; override val position = lease.position
                    override suspend fun finish(resume: Boolean) = finish(resume, true)
                    override suspend fun finish(resume: Boolean, allowRewind: Boolean) {
                        restores++; lease.finish(resume, allowRewind)
                    }
                }
            }, { lease ->
                if (race) throw VoiceContextUnavailable(VoiceReadiness.COVERAGE_GAP)
                VoiceContext.load(graph.db, lease.episode, lease.position)
            }, {
                connections++
                object : VoiceConnection {
                    override suspend fun answer(input: VoiceInput, output: VoiceOutput, phase: (String) -> Unit, text: (String) -> Unit) {
                        text("Synthetic successful retry")
                    }
                    override fun close() {}
                }
            }, { object : VoiceOutput {
                override suspend fun play(pcm: ByteArray) {}
                override suspend fun drain() {}
                override fun close() {}
            } }, preflight = {
                if (preflight) { val (episodeId, position) = graph.playback.voicePosition() ?: throw VoiceContextUnavailable(VoiceReadiness.EPISODE_UNAVAILABLE); VoiceContext.check(graph.db, episodeId, position) }
            }).also { coordinator = it }
            fun ask() = main { voice.start({ object : VoiceInput {
                override fun start() { error("Synthetic provider must not start microphone") }
                override fun read(): ByteArray? = error("Synthetic provider must not read microphone")
                override fun close() {}
            } }) }
            ask(); await { voice.state.value.error != null && !voice.state.value.active }
            assertEquals(VoiceReadiness.MISSING_TRANSCRIPT.feedback, voice.state.value.error)
            assertEquals(0, connections); assertEquals(0, restores); assertEquals(0, main { pausedEvents })
            assertTrue(main { c.isPlaying })
            preflight = false; race = true
            ask(); await { restores == 1 && !voice.state.value.active && main { c.isPlaying } }
            assertEquals(0, connections)
            assertTrue("Readiness race must not rewind", main { c.currentPosition >= captured })
            // Paused state is restored without starting playback.
            main { c.pause(); c.seekTo(60000) }
            await { main { !c.isPlaying && c.currentPosition == 60000L } }
            ask(); await { restores == 2 && !voice.state.value.active }
            assertFalse(main { c.playWhenReady }); assertEquals(60000L, main { c.currentPosition })
            // Once partial context becomes ready, the same coordinator retries successfully.
            dao.putTranscript(TranscriptEntity(id, "r", "fixture", "tiny", "en", state = "partial"))
            dao.putSegments(listOf(SegmentEntity(id, "r", 40000, 59000, "Synthetic earlier speech")))
            preflight = true; race = false
            ask(); await { restores == 3 && !voice.state.value.active }
            assertEquals(1, connections); assertNull(voice.state.value.error)
            assertFalse(main { c.playWhenReady }); assertEquals(60000L, main { c.currentPosition })
            // Ordinary successful completion still honors the existing rewind preference.
            main { c.play() }; await { main { c.isPlaying } }
            ask(); await { restores == 4 && !voice.state.value.active && main { c.isPlaying } }
            assertEquals(2, connections)
            assertTrue(main { c.currentPosition < captured && c.currentPosition >= captured - 3000 })
            // User seeks and item replacements revoke the existing lease, never restart playback.
            val seekLease = graph.playback.pauseForVoice({}, 3000)
            main { c.seekTo(30000) }; delay(250); seekLease.finish(true, false)
            assertFalse(main { c.playWhenReady }); assertEquals(30000L, main { c.currentPosition })
            main { c.play() }; await { main { c.isPlaying } }
            val replaced = graph.playback.pauseForVoice({}, 3000)
            main { c.setMediaItem(MediaItem.Builder().setMediaId("replacement").setUri(android.net.Uri.fromFile(file)).build(), 10000) }
            delay(250); replaced.finish(true, false)
            assertFalse(main { c.playWhenReady }); assertEquals("replacement", main { c.currentMediaItem?.mediaId })
            main { c.clearMediaItems() }
            await { main { c.currentMediaItem == null } }
            main { graph.voice.start({ error("Empty queue must not open microphone") }) }
            await { graph.voice.state.value.error != null && !graph.voice.state.value.active }
            assertEquals(VoiceReadiness.EPISODE_UNAVAILABLE.feedback, graph.voice.state.value.error)
            assertNull(graph.playback.voicePosition())
            try { graph.playback.pauseForVoice({}); fail("Cleared queue must be expected readiness") }
            catch (error: VoiceContextUnavailable) { assertEquals(VoiceReadiness.EPISODE_UNAVAILABLE, error.readiness) }
            main { c.removeListener(listener) }
        } finally {
            main { coordinator?.cancel(false); player?.pause(); player?.clearMediaItems(); player?.release() }
            scope.cancel()
            dao.deletePodcast(id)
            graph.settings.flag("transcribeOnPlayback", originalPreferences.transcribeOnPlayback)
            file.delete()
        }
    }
}

private fun MainActivity.setContentForReadiness(content: @Composable () -> Unit) {
    setContent(content = content)
}
