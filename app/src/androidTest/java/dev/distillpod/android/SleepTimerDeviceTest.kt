package dev.distillpod.android

import android.os.Build
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.*
import dev.distillpod.android.sync.SyncViewEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** The service-owned sleep timer pauses real playback and persists progress, on disposable emulators only. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SleepTimerDeviceTest {
    // A visible activity lets the playback service start in the foreground, as in ordinary use.
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = instrumentation.targetContext.appGraph

    private suspend fun awaitCondition(phase: String, timeoutMs: Long = 15_000, check: suspend () -> Boolean) {
        try { withTimeout(timeoutMs) { while (!check()) delay(50) } }
        catch (failure: TimeoutCancellationException) {
            val state = graph.playback.state.value
            throw AssertionError("Sleep timer phase '$phase' timed out; playing=${state.playing}, queueSize=${state.queue.size}, " +
                "positionMs=${state.positionMs}, timer=${graph.playback.sleepTimer.value}, error=${state.error}", failure)
        }
    }

    /** Seeds two synthetic downloaded episodes, runs [block] and restores the previous library state. */
    private fun fixture(block: suspend (first: String, second: String) -> Unit) = runBlocking<Unit> {
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic"))
        graph.ready.await()
        val dao = graph.db.library()
        val oldQueue = dao.queue(); val oldSession = dao.playerSession()
        val oldView = graph.db.sync().view("queue_library")
        val oldTranscribe = graph.settings.flow.first().transcribeOnPlayback
        val tag = "sleep-timer-" + UUID.randomUUID()
        val first = "$tag-first"; val second = "$tag-second"
        val file = File(instrumentation.targetContext.cacheDir, "$tag.wav")
        instrumentation.context.assets.open("jfk.wav").use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        var primary: Throwable? = null
        try {
            graph.settings.flag("transcribeOnPlayback", false)
            withTimeout(10_000) { graph.preferences.first { !it.transcribeOnPlayback } }
            graph.playback.cancelSleepTimer()
            graph.playback.pause(); graph.playback.clear()
            awaitCondition("initial clear") { graph.playback.state.value.queue.isEmpty() }
            dao.putPodcast(PodcastEntity(tag, "https://example.test/$tag", "Sleep timer fixture"))
            listOf(first, second).forEach { id ->
                dao.putEpisode(EpisodeEntity(id, tag, id, "Sleep timer item", "https://example.test/$id",
                    localFile = file.absolutePath, downloadState = "downloaded"))
            }
            block(first, second)
        } catch (failure: Throwable) { primary = failure; throw failure } finally {
            try {
                graph.playback.cancelSleepTimer()
                graph.playback.pause(); graph.playback.clear()
                awaitCondition("cleanup clear") { graph.playback.state.value.queue.isEmpty() }
                graph.db.withTransaction {
                    dao.deletePodcast(tag); dao.clearQueue(); dao.insertQueue(oldQueue)
                    if (oldSession != null) dao.putPlayerSession(oldSession)
                    graph.db.sync().putView(oldView ?: SyncViewEntity("queue_library"))
                }
                graph.settings.flag("transcribeOnPlayback", oldTranscribe)
                file.delete()
            } catch (cleanup: Throwable) { val original = primary; if (original != null) original.addSuppressed(cleanup) else throw cleanup }
        }
    }

    @Test fun countdownPausesPlaybackRestoresVolumeAndPersistsProgress() = fixture { id, _ ->
        graph.playback.play(id, 1_000)
        awaitCondition("playing") { graph.playback.state.value.let { it.playing && it.episodeId == id } }
        graph.playback.startSleepTimer(3_000)
        awaitCondition("timer published") { graph.playback.sleepTimer.value?.counting == true }
        awaitCondition("timer paused playback") { !graph.playback.state.value.playing && graph.playback.sleepTimer.value == null }
        val (position, volume, pauseAtEnd) = withContext(Dispatchers.Main) {
            val player = fixtureServicePlayer(id)
            assertFalse("The timer pauses; it does not stop or release the player", player.playWhenReady)
            assertEquals(id, player.currentMediaItem?.mediaId)
            Triple(player.currentPosition, player.volume, player.pauseAtEndOfMediaItems)
        }
        assertEquals("Volume is restored after the fade", 1f, volume, 0f)
        assertFalse(pauseAtEnd)
        assertTrue("About three seconds of playing time elapsed, was $position", position in 3_000..6_500)
        // The pause is a progress boundary: the exact paused position reaches Room promptly.
        awaitCondition("progress persisted", 10_000) {
            val saved = graph.db.library().episode(id)!!.positionMs
            kotlin.math.abs(saved - position) <= 500
        }
        delay(1_500)
        assertFalse("A fired timer does not resume or pause again", graph.playback.state.value.playing)
    }

    @Test fun endOfEpisodePausesAtTheItemEndWithoutAdvancingAndRecordsCompletion() = fixture { first, second ->
        // Arm the timer before slower queue work, leaving the ~11 s clip ample time to reach its end.
        graph.playback.play(first, 3_000)
        awaitCondition("playing") { graph.playback.state.value.let { it.playing && it.episodeId == first } }
        graph.playback.sleepAtEndOfEpisode()
        graph.playback.enqueue(second)
        awaitCondition("next item queued") { graph.playback.state.value.queue == listOf(first, second) }
        awaitCondition("end of episode published") { graph.playback.sleepTimer.value?.endOfEpisode == true }
        assertTrue(withContext(Dispatchers.Main) { fixtureServicePlayer(first).pauseAtEndOfMediaItems })
        awaitCondition("paused at item end", 20_000) { !graph.playback.state.value.playing && graph.playback.sleepTimer.value == null }
        withContext(Dispatchers.Main) {
            val player = fixtureServicePlayer(first)
            assertEquals("The next item must not start", first, player.currentMediaItem?.mediaId)
            assertFalse(player.playWhenReady)
            assertFalse(player.pauseAtEndOfMediaItems)
            assertEquals(1f, player.volume, 0f)
        }
        awaitCondition("finished item recorded", 10_000) { graph.db.library().episode(first)!!.completed }
        val next = graph.db.library().episode(second)!!
        assertFalse(next.completed)
        assertEquals("The next item was not started", 0L, next.positionMs)
    }

    @Test fun cancelledTimerLeavesPlaybackRunning() = fixture { id, _ ->
        graph.playback.play(id, 0)
        awaitCondition("playing") { graph.playback.state.value.let { it.playing && it.episodeId == id } }
        graph.playback.startSleepTimer(2_000)
        awaitCondition("timer published") { graph.playback.sleepTimer.value != null }
        graph.playback.cancelSleepTimer()
        assertNull(graph.playback.sleepTimer.value)
        delay(3_000)
        assertTrue("Cancelled timer must not pause", graph.playback.state.value.playing)
        assertEquals(1f, withContext(Dispatchers.Main) { fixtureServicePlayer(id).volume }, 0f)
    }
}
