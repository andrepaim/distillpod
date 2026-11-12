package dev.distillpod.android

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.distillpod.android.data.*
import dev.distillpod.android.features.AppViewModel
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

/** Explicit offline phone protocol; never runs against the production application. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class QueueControlsPhoneDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun isolatedQueueMovesAndConfirmedClearPreserveProgressAndFiles() = runBlocking<Unit> {
        val context = compose.activity.applicationContext
        check(context.packageName == "dev.distillpod.android.validation.queue54")
        check(context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_DENIED)
        val graph = context.appGraph
        graph.ready.await()
        val dao = graph.db.library()
        check(dao.episodes().isEmpty() && dao.queue().isEmpty()) { "Use a fresh offline queue validation app" }
        val tag = "phone-queue-" + UUID.randomUUID()
        val ids = listOf("$tag-a", "$tag-b")
        val file = File(context.cacheDir, "$tag.wav")
        val samples = dev.distillpod.core.readWhisperWave(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
                .open("jfk.wav").use { it.readBytes() })
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { pcm.putShort((it * 32768).toInt().coerceIn(-32768, 32767).toShort()) }
        dev.distillpod.core.PcmWaveWriter(file, 16000, 1).use { writer ->
            repeat(20) { writer.append(ByteBuffer.wrap(pcm.array())) }
            writer.finish()
        }
        val duration = samples.size.toLong() * 20 * 1000 / 16000
        suspend fun awaitQueue(expected: List<String>) = withTimeout(15000) {
            graph.playback.state.first { it.queue == expected }
            while (dao.queue().map { it.episodeId } != expected) delay(50)
        }
        fun clearDialog() {
            compose.onNodeWithTag("queue-list").performScrollToNode(hasText("Clear queue"))
            compose.onNodeWithText("Clear queue").performClick()
            compose.onNodeWithText("Clear queue and stop playback?").assertIsDisplayed()
        }
        val player = graph.playback.sessionPlayer()
        val oldVolume = withContext(Dispatchers.Main) { player.volume }
        val oldTranscribe = graph.settings.flow.first().transcribeOnPlayback
        var primary: Throwable? = null
        try {
            // Mute this validation player only; never change the phone's media volume.
            withContext(Dispatchers.Main) { player.volume = 0f; check(player.volume == 0f) }
            graph.settings.flag("transcribeOnPlayback", false)
            withTimeout(10000) { graph.preferences.first { !it.transcribeOnPlayback } }
            dao.putPodcast(PodcastEntity(tag, "https://example.test/$tag", "Phone queue fixture"))
            ids.forEachIndexed { index, id ->
                dao.putEpisode(EpisodeEntity(id, tag, id, "Phone queue item $index", "https://example.test/$id",
                    localFile = file.absolutePath, downloadState = "downloaded", durationMs = duration))
            }
            graph.playback.play(ids[0], 2000)
            graph.playback.pause()
            graph.playback.seek(2000)
            graph.playback.enqueue(ids[1])
            awaitQueue(ids)
            withContext(Dispatchers.Main) {
                ViewModelProvider(compose.activity, AppViewModel.factory(graph))[AppViewModel::class.java].open("queue")
            }
            compose.waitUntil(15000) { compose.onAllNodesWithText("Clear queue").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("queue-list").performScrollToNode(hasContentDescription("Move Phone queue item 0 up"))
            compose.onNodeWithContentDescription("Move Phone queue item 0 up").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Move Phone queue item 0 down").performClick()
            awaitQueue(ids.reversed())
            assertEquals(ids[0], graph.playback.state.value.episodeId)
            assertFalse(graph.playback.state.value.playing)
            withContext(Dispatchers.Main) { assertEquals(2000L, player.currentPosition) }
            compose.onNodeWithTag("queue-list").performScrollToNode(hasContentDescription("Move Phone queue item 0 up"))
            compose.onNodeWithContentDescription("Move Phone queue item 0 up").performClick()
            awaitQueue(ids)
            graph.playback.toggle()
            withTimeout(10000) { graph.playback.state.first { it.playing } }
            compose.onNodeWithTag("queue-list").performScrollToNode(hasContentDescription("Move Phone queue item 0 down"))
            compose.onNodeWithContentDescription("Move Phone queue item 0 down").performClick()
            awaitQueue(ids.reversed())
            assertEquals(ids[0], graph.playback.state.value.episodeId)
            assertTrue(graph.playback.state.value.playing)
            withContext(Dispatchers.Main) { assertEquals(android.net.Uri.fromFile(file), player.currentMediaItem!!.localConfiguration!!.uri) }
            // Play next while playing edits the playlist without re-preparing or seeking the current item.
            assertEnqueueKeepsPlaying(ids[0]) {
                graph.playback.enqueue(ids[1], next = true)
                awaitQueue(ids)
            }
            assertEquals(ids[0], graph.playback.state.value.episodeId)
            graph.playback.pause()
            graph.playback.seek(4567)
            clearDialog()
            compose.onNodeWithText("Cancel").performClick()
            assertEquals(ids, graph.playback.state.value.queue)
            clearDialog()
            compose.onNodeWithText("Clear and stop").performClick()
            awaitQueue(emptyList())
            withTimeout(10000) { while (dao.episode(ids[0])!!.positionMs != 4567L) delay(50) }
            assertNull(dao.playerSession()?.episodeId)
            assertEquals(duration, dao.episode(ids[0])!!.durationMs)
            assertEquals("downloaded", dao.episode(ids[0])!!.downloadState)
            assertFalse(dao.episode(ids[0])!!.completed)
            assertTrue(file.exists())
            withContext(Dispatchers.Main) { assertFalse(player.playWhenReady) }
            compose.onNodeWithText("Your queue is clear").assertIsDisplayed()
            graph.playback.enqueue(ids[1])
            awaitQueue(listOf(ids[1]))
            withContext(Dispatchers.Main) { assertFalse(player.playWhenReady); assertFalse(player.isPlaying) }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                graph.playback.pause()
                graph.playback.clear()
                awaitQueue(emptyList())
                withContext(Dispatchers.Main) { player.volume = oldVolume }
                graph.settings.flag("transcribeOnPlayback", oldTranscribe)
                dao.deletePodcast(tag)
                file.delete()
            } catch (cleanup: Throwable) {
                val original = primary
                if (original != null) original.addSuppressed(cleanup) else throw cleanup
            }
        }
    }
}
