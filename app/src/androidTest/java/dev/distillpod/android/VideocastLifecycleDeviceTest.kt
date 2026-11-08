package dev.distillpod.android

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
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

    @Test fun videoExposesAFullscreenAction() = exercise(pending = false, fullscreen = true)
    @Test fun rotationAutomaticallyEntersAndExitsWithoutResettingPlayback() = exercise(pending = false, automatic = true)
    @Test fun backgroundingRestoresAudioWhileCompositionIsStopped() = exercise(pending = false)
    @Test fun backgroundingCancelsAPendingVideoRequestBeforeActivityResume() = exercise(pending = true)

    private fun exercise(pending: Boolean, fullscreen: Boolean = false, automatic: Boolean = false) = runBlocking<Unit> {
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val oldRotation = android.provider.Settings.System.getInt(compose.activity.contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 0)
        val oldOrientation = compose.activity.requestedOrientation
        val oldImmersiveConfirmation = android.provider.Settings.Secure.getString(compose.activity.contentResolver, "immersive_mode_confirmations")
        fun immersiveConfirmation(value: String?) {
            instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            try {
                check(android.provider.Settings.Secure.putString(compose.activity.contentResolver, "immersive_mode_confirmations", value))
            } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        }
        fun rotationSetting(value: Int) {
            instrumentation.uiAutomation.executeShellCommand("settings put system accelerometer_rotation $value").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() }
            }
        }
        val graph = compose.activity.applicationContext.appGraph
        graph.ready.await()
        val tag = "videocast-lifecycle-${UUID.randomUUID()}"
        val file = File(graph.context.cacheDir, "$tag.wav")
        instrumentation.context.assets.open("jfk.wav").use { input -> file.outputStream().use(input::copyTo) }
        val dao = graph.db.library()
        val automaticTranscription = graph.settings.flow.first().transcribeOnPlayback
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
            // Match Android CTS's immersive fixture. The platform's delayed education
            // window consumes Back independently of the application's callback.
            // This class is emulator-only, and the original setting is restored below.
            immersiveConfirmation("confirmed")
            graph.playback.pause(); graph.playback.clear()
            dao.putPodcast(PodcastEntity(tag, "https://example.test/$tag", "Lifecycle fixture"))
            dao.putEpisode(EpisodeEntity(tag, tag, tag, "Lifecycle episode",
                if (pending) "https://www.youtube.com/watch?v=abcdefghijk" else "https://example.test/video.mp4",
                mimeType = "video/mp4", localFile = file.absolutePath, downloadState = "downloaded"))
            graph.playback.play(tag, 2_000)
            awaitPlayer("initial playback") { it.isPlaying }
            graph.playback.pause(); graph.playback.speed(1.25f)
            awaitPlayer("paused and prepared") { !it.isPlaying && it.playbackState == Player.STATE_READY }
            withContext(Dispatchers.Main) {
                if (fullscreen) {
                    rotationSetting(0)
                    compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    // Exercise the application's navigation BackHandler as well as the player.
                    androidx.lifecycle.ViewModelProvider(compose.activity, AppViewModel.factory(graph))[AppViewModel::class.java].open("player")
                } else compose.activity.setContent {
                    DistillTheme { Surface { Column(Modifier.padding(48.dp)) { VideocastPlayer(playback, tag, audioOnlyArtwork = {}) } } }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Video", useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
            if (fullscreen) compose.onNodeWithText("Video", useUnmergedTree = true).performScrollTo()
            // Controller pause is optimistic. Capture the invariant only after the
            // service's paused position has stopped changing, before requesting video.
            var position = -1L
            withTimeout(5_000) {
                var stable = 0
                while (stable < 3) {
                    val next = withContext(Dispatchers.Main) {
                        check(!player.playWhenReady && player.playbackState == Player.STATE_READY)
                        player.currentPosition
                    }
                    stable = if (next == position) stable + 1 else 0
                    position = next
                    delay(50)
                }
            }
            compose.onNodeWithText("Video", useUnmergedTree = true).performClick()
            compose.waitForIdle()
            if (pending) withTimeout(10_000) { requestStarted.await() }
            else awaitPlayer("video enabled in foreground") { C.TRACK_TYPE_VIDEO !in it.trackSelectionParameters.disabledTrackTypes }
            if (automatic) {
                rotationSetting(1)
                withContext(Dispatchers.Main) { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Exit fullscreen").fetchSemanticsNodes().isNotEmpty() }
                val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
                device.pressBack()
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Enter fullscreen").fetchSemanticsNodes().isNotEmpty() }
                compose.mainClock.advanceTimeBy(1000)
                compose.onNodeWithContentDescription("Exit fullscreen").assertDoesNotExist()
                compose.onNodeWithContentDescription("Enter fullscreen").assertIsDisplayed()
                withContext(Dispatchers.Main) { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
                withContext(Dispatchers.Main) { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Exit fullscreen").fetchSemanticsNodes().isNotEmpty() }
                withContext(Dispatchers.Main) { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Enter fullscreen").fetchSemanticsNodes().isNotEmpty() }
                withContext(Dispatchers.Main) {
                    assertEquals(position, player.currentPosition)
                    assertEquals(1.25f, player.playbackParameters.speed, .001f)
                    assertTrue(C.TRACK_TYPE_VIDEO !in player.trackSelectionParameters.disabledTrackTypes)
                }
            }
            if (fullscreen) {
                rotationSetting(0)
                val activity = compose.activity
                compose.onNodeWithContentDescription("Enter fullscreen").assertIsDisplayed().performClick()
                compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                    compose.onAllNodesWithContentDescription("Exit fullscreen").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Exit fullscreen").assertExists()
                assertSame("Rotation must retain the activity and composition", activity, compose.activity)
                withContext(Dispatchers.Main) {
                    assertFalse(player.playWhenReady)
                    assertEquals(position, player.currentPosition)
                    assertEquals(1.25f, player.playbackParameters.speed, 0.001f)
                    assertTrue(C.TRACK_TYPE_VIDEO !in player.trackSelectionParameters.disabledTrackTypes)
                }
                val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
                device.pressBack()
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Exit fullscreen").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithContentDescription("Enter fullscreen").assertIsDisplayed()
                withContext(Dispatchers.Main) { assertEquals(position, player.currentPosition) }
            }
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
            graph.settings.flag("transcribeOnPlayback", automaticTranscription)
            modeScope.cancel()
            rotationSetting(oldRotation)
            withContext(Dispatchers.Main) { compose.activity.requestedOrientation = oldOrientation }
            immersiveConfirmation(oldImmersiveConfirmation)
        }
    }
}
