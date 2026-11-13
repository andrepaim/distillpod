package dev.distillpod.android

import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.*
import dev.distillpod.android.sync.SyncViewEntity
import dev.distillpod.core.SpeedPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Skip silence, volume boost and per-show speeds on the real service player (#183), on disposable emulators only. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackEffectsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = instrumentation.targetContext.appGraph

    private suspend fun awaitCondition(phase: String, timeoutMs: Long = 15_000, check: suspend () -> Boolean) {
        try { withTimeout(timeoutMs) { while (!check()) delay(50) } }
        catch (failure: TimeoutCancellationException) {
            val state = graph.playback.state.value
            throw AssertionError("Playback effects phase '$phase' timed out; playing=${state.playing}, episode=${state.episodeId}, " +
                "speed=${state.speed}, boost=${graph.playbackEvents.volumeBoost.value}, error=${state.error}", failure)
        }
    }

    /** Two synthetic shows with one downloaded episode each; restores the library, effects and speeds afterwards. */
    private fun fixture(block: suspend (first: String, firstShow: String, second: String, secondShow: String) -> Unit) = runBlocking<Unit> {
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic"))
        graph.ready.await()
        val dao = graph.db.library()
        val oldQueue = dao.queue(); val oldSession = dao.playerSession()
        val oldView = graph.db.sync().view("queue_library")
        val oldPrefs = graph.settings.flow.first()
        val oldSpeeds = graph.settings.speeds.first()
        val tag = "effects-" + UUID.randomUUID()
        val shows = listOf("$tag-show-a", "$tag-show-b")
        val episodes = shows.map { "$it-episode" }
        val file = File(instrumentation.targetContext.cacheDir, "$tag.wav")
        instrumentation.context.assets.open("jfk.wav").use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        var primary: Throwable? = null
        try {
            graph.settings.flag("transcribeOnPlayback", false)
            withTimeout(10_000) { graph.preferences.first { !it.transcribeOnPlayback } }
            graph.playback.cancelSleepTimer()
            graph.playback.pause(); graph.playback.clear()
            awaitCondition("initial clear") { graph.playback.state.value.queue.isEmpty() }
            shows.zip(episodes).forEach { (show, id) ->
                dao.putPodcast(PodcastEntity(show, "https://example.test/$show", "Effects fixture"))
                dao.putEpisode(EpisodeEntity(id, show, id, "Effects item", "https://example.test/$id",
                    localFile = file.absolutePath, downloadState = "downloaded"))
            }
            block(episodes[0], shows[0], episodes[1], shows[1])
        } catch (failure: Throwable) { primary = failure; throw failure } finally {
            try {
                graph.playback.cancelSleepTimer()
                graph.playback.pause(); graph.playback.clear()
                awaitCondition("cleanup clear") { graph.playback.state.value.queue.isEmpty() }
                graph.settings.updateSpeeds { oldSpeeds }
                graph.settings.flag("skipSilence", oldPrefs.skipSilence)
                graph.settings.flag("volumeBoost", oldPrefs.volumeBoost)
                graph.settings.flag("transcribeOnPlayback", oldPrefs.transcribeOnPlayback)
                graph.db.withTransaction {
                    shows.forEach { dao.deletePodcast(it) }; dao.clearQueue(); dao.insertQueue(oldQueue)
                    if (oldSession != null) dao.putPlayerSession(oldSession)
                    graph.db.sync().putView(oldView ?: SyncViewEntity("queue_library"))
                }
                file.delete()
            } catch (cleanup: Throwable) { val original = primary; if (original != null) original.addSuppressed(cleanup) else throw cleanup }
        }
    }

    @Test fun skipSilenceAndVolumeBoostApplyToTheServicePlayerAndComposeWithTheSleepTimer() = fixture { id, _, _, _ ->
        graph.settings.flag("skipSilence", true)
        graph.settings.flag("volumeBoost", true)
        graph.playback.play(id, 0)
        awaitCondition("playing") { graph.playback.state.value.let { it.playing && it.episodeId == id } }
        val session = withContext(Dispatchers.Main) {
            val player = fixtureServicePlayer(id)
            awaitCondition("skip silence applied") { player.skipSilenceEnabled }
            player.audioSessionId
        }
        awaitCondition("boost attached") { graph.playbackEvents.volumeBoost.value.session == session }
        assertTrue(session != 0)
        assertFalse(graph.playbackEvents.volumeBoost.value.unavailable)

        // The sleep timer's fade and pause work with the boost on, and leave it attached.
        graph.playback.startSleepTimer(3_000)
        awaitCondition("timer paused playback") { !graph.playback.state.value.playing && graph.playback.sleepTimer.value == null }
        withContext(Dispatchers.Main) {
            val player = fixtureServicePlayer(id)
            assertFalse(player.playWhenReady)
            assertEquals("Volume is restored after the fade", 1f, player.volume, 0f)
            assertEquals(session, player.audioSessionId)
        }
        assertEquals(session, graph.playbackEvents.volumeBoost.value.session)

        graph.settings.flag("skipSilence", false)
        graph.settings.flag("volumeBoost", false)
        awaitCondition("boost released") { graph.playbackEvents.volumeBoost.value.session == null }
        withContext(Dispatchers.Main) { awaitCondition("skip silence off") { !fixtureServicePlayer(id).skipSilenceEnabled } }
    }

    @Test fun aShowsOwnSpeedAppliesOnItemChangeAndChoicesGoToTheRightSlot() = fixture { first, firstShow, second, _ ->
        graph.settings.updateSpeeds { SpeedPreferences(global = 1.1f, shows = mapOf(firstShow to 1.7f)) }
        graph.playback.play(first, 0)
        awaitCondition("own show speed") { graph.playback.state.value.let { it.episodeId == first && it.speed == 1.7f } }
        graph.playback.play(second, 0)
        awaitCondition("global speed") { graph.playback.state.value.let { it.episodeId == second && it.speed == 1.1f } }
        graph.playback.speed(1.4f)
        awaitCondition("global choice stored") { graph.settings.speeds.first().global == 1.4f }
        assertEquals(1.7f, graph.settings.speeds.first().shows[firstShow])
        graph.playback.play(first, 0)
        awaitCondition("own show speed again") { graph.playback.state.value.let { it.episodeId == first && it.speed == 1.7f } }
        graph.playback.speed(2f)
        awaitCondition("show choice stored") { graph.settings.speeds.first().shows[firstShow] == 2f }
        assertEquals(1.4f, graph.settings.speeds.first().global)
        // Forgetting the show's speed returns the current item to the global speed.
        graph.settings.updateSpeeds { it.forget(firstShow) }
        awaitCondition("back to global") { graph.playback.state.value.speed == 1.4f }
        withContext(Dispatchers.Main) { assertEquals(1.4f, fixtureServicePlayer(first).playbackParameters.speed, 0f) }
    }
}
