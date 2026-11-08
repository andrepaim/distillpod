package dev.distillpod.android

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.*
import dev.distillpod.android.features.*
import dev.distillpod.android.playback.PlaybackRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Stopping the real Compose host must restore audio before its frame clock resumes. */
@RunWith(AndroidJUnit4::class)
class VideocastLifecycleDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun backgroundingRestoresAudioWhileCompositionIsStopped() = exercise(pending = false)
    @Test fun backgroundingCancelsAPendingVideoRequestBeforeActivityResume() = exercise(pending = true)

    private fun exercise(pending: Boolean) = runBlocking<Unit> {
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph = compose.activity.applicationContext.appGraph
        graph.ready.await()
        val tag = "videocast-lifecycle-${UUID.randomUUID()}"
        val file = File(graph.context.cacheDir, "$tag.wav")
        instrumentation.context.assets.open("jfk.wav").use { input -> file.outputStream().use(input::copyTo) }
        val dao = graph.db.library()
        val automatic = graph.settings.flow.first().transcribeOnPlayback
        graph.settings.flag("transcribeOnPlayback", false)
        val modeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val requestStarted = CompletableDeferred<Unit>()
        val requestCancelled = CompletableDeferred<Unit>()
        val playback = if (!pending) graph.playback else PlaybackRepository(graph.context, graph.db, modeScope,
            prepareVideo = {
                requestStarted.complete(Unit)
                try { awaitCancellation() } finally { requestCancelled.complete(Unit) }
            })
        val player = graph.playback.sessionPlayer()
        suspend fun awaitPlayer(label: String, predicate: (Player) -> Boolean) {
            try { withTimeout(10_000) { while (!withContext(Dispatchers.Main) { predicate(player) }) delay(50) } }
            catch (e: TimeoutCancellationException) { throw AssertionError("Player did not reach: $label", e) }
        }
        try {
            graph.playback.pause(); graph.playback.clear()
            dao.putPodcast(PodcastEntity(tag, "https://example.test/$tag", "Lifecycle fixture"))
            dao.putEpisode(EpisodeEntity(tag, tag, tag, "Lifecycle episode",
                if (pending) "https://www.youtube.com/watch?v=abcdefghijk" else "https://example.test/video.mp4",
                mimeType = "video/mp4", localFile = file.absolutePath, downloadState = "downloaded"))
            graph.playback.play(tag, 2_000)
            awaitPlayer("initial playback") { it.isPlaying }
            graph.playback.pause(); graph.playback.speed(1.25f)
            awaitPlayer("paused and prepared") { !it.isPlaying && it.playbackState == Player.STATE_READY }
            val position = withContext(Dispatchers.Main) { player.currentPosition }
            withContext(Dispatchers.Main) { compose.activity.setContent {
                DistillTheme { Surface { Column(Modifier.padding(48.dp)) { VideocastPlayer(playback, tag, audioOnlyArtwork = {}) } } }
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Video", useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("Video", useUnmergedTree = true).performClick()
            compose.waitForIdle()
            if (pending) withTimeout(10_000) { requestStarted.await() }
            else awaitPlayer("video enabled in foreground") { C.TRACK_TYPE_VIDEO !in it.trackSelectionParameters.disabledTrackTypes }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            // Do not use Compose's idling/frame synchronization while the host is stopped.
            withTimeout(10_000) { while (withContext(Dispatchers.Main) {
                compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }) delay(50) }
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("lifecycle_phase", "host_stopped_waiting_for_audio") })
            if (pending) withTimeout(10_000) { requestCancelled.await() }
            awaitPlayer("audio restored while stopped") { C.TRACK_TYPE_VIDEO in it.trackSelectionParameters.disabledTrackTypes }
            withContext(Dispatchers.Main) {
                assertFalse(player.playWhenReady)
                assertEquals(position, player.currentPosition)
                assertEquals(1.25f, player.playbackParameters.speed, 0.001f)
                assertEquals(tag, player.currentMediaItem?.mediaId)
            }
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            withContext(Dispatchers.Main) { compose.activity.setContent {} }
            graph.playback.pause(); graph.playback.clear(); graph.playback.speed(1f)
            withTimeout(10_000) { graph.playback.state.first { it.queue.isEmpty() } }
            delay(300); dao.deletePodcast(tag); file.delete()
            graph.settings.flag("transcribeOnPlayback", automatic)
            modeScope.cancel()
        }
    }
}
