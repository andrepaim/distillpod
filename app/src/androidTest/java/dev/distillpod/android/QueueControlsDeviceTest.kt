package dev.distillpod.android

import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.*
import dev.distillpod.android.features.AppViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Synthetic queue fixture, only on disposable emulators. */
@RunWith(AndroidJUnit4::class)
class QueueControlsDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun queueControlsPreservePlaybackAndPersistOrderThenConfirmedClear()=runBlocking<Unit> {
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic")) { "Use a disposable emulator" }
        val context=compose.activity.applicationContext
        val graph=context.appGraph
        graph.ready.await()
        val dao=graph.db.library()
        val oldQueue=dao.queue()
        val oldSession=dao.playerSession()
        val oldTranscribeOnPlayback=graph.settings.flow.first().transcribeOnPlayback
        val tag="queue-controls-"+UUID.randomUUID()
        val ids=listOf("a","b","c").map { "$tag-$it" }
        val file=File(context.cacheDir,"$tag.wav")
        // Long local audio avoids auto-advance racing slower CI accessibility interactions.
        val samples=dev.distillpod.core.readWhisperWave(
            InstrumentationRegistry.getInstrumentation().context.assets.open("jfk.wav").use { it.readBytes() })
        val wave=java.nio.ByteBuffer.allocate(samples.size*2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        samples.forEach { wave.putShort((it*32768).toInt().coerceIn(-32768,32767).toShort()) }
        dev.distillpod.core.PcmWaveWriter(file,16000,1).use { writer ->
            repeat(20) { writer.append(java.nio.ByteBuffer.wrap(wave.array())) };writer.finish()
        }
        suspend fun awaitQueue(expected:List<String>) {
            withTimeout(15000) { graph.playback.state.first { it.queue==expected } }
            withTimeout(15000) { while(dao.queue().map { it.episodeId }!=expected) delay(50) }
        }
        try {
            graph.settings.flag("transcribeOnPlayback",false)
            withTimeout(10000) { graph.preferences.first { !it.transcribeOnPlayback } }
            graph.playback.pause();graph.playback.clear();awaitQueue(emptyList())
            dao.putPodcast(PodcastEntity(tag,"https://example.test/$tag","Queue controls fixture"))
            ids.forEachIndexed { index,id -> dao.putEpisode(EpisodeEntity(id,tag,id,"Queue item $index",
                "https://example.test/$id",localFile=file.absolutePath,downloadState="downloaded",durationMs=samples.size.toLong()*20*1000/16000)) }
            graph.playback.play(ids[0],2000)
            graph.playback.pause()
            graph.playback.enqueue(ids[1]);graph.playback.enqueue(ids[2])
            awaitQueue(ids)
            val vm=withContext(Dispatchers.Main) {
                ViewModelProvider(compose.activity,AppViewModel.factory(graph))[AppViewModel::class.java].also { it.open("queue") }
            }
            compose.waitUntil(15000) { compose.onAllNodesWithText("Clear queue").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("queue-list").performScrollToNode(hasContentDescription("Move Queue item 0 up"))
            compose.onNodeWithContentDescription("Move Queue item 0 up").assertIsNotEnabled()
            compose.onNodeWithTag("queue-list").performScrollToNode(hasContentDescription("Move Queue item 0 down"))
            compose.onNodeWithContentDescription("Move Queue item 0 down").performClick()
            awaitQueue(listOf(ids[1],ids[0],ids[2]))
            assertEquals(ids[0],graph.playback.state.value.episodeId)
            assertFalse(graph.playback.state.value.playing)
            val player=graph.playback.sessionPlayer()
            val position=withContext(Dispatchers.Main) { player.currentPosition }
            assertTrue(position in 1900..2500)
            // Repeated taps are retained even while a preceding controller command suspends.
            withContext(Dispatchers.Main) { vm.moveQueue(ids[0],1);vm.moveQueue(ids[0],-1);vm.moveQueue(ids[0],-1) }
            awaitQueue(ids)
            // Missing identities and out-of-range moves must not move a different episode.
            graph.playback.move("missing",1);graph.playback.move(ids[0],-1);graph.playback.move(ids[2],1)
            graph.playback.move(ids[0],Int.MAX_VALUE);graph.playback.move(ids[1],0)
            awaitQueue(ids)
            // External controller changes use the same displayed and persisted source of truth.
            withContext(Dispatchers.Main) { player.moveMediaItem(2,0) }
            awaitQueue(listOf(ids[2],ids[0],ids[1]))
            compose.onNodeWithTag("queue-list").performScrollToNode(hasContentDescription("Move Queue item 2 up"))
            compose.onNodeWithContentDescription("Move Queue item 2 up").assertIsNotEnabled()
            graph.playback.play(ids[0],3000)
            withTimeout(10000) { graph.playback.state.first { it.playing } }
            graph.playback.move(ids[0],1)
            awaitQueue(listOf(ids[2],ids[1],ids[0]))
            assertEquals(ids[0],graph.playback.state.value.episodeId)
            assertTrue(graph.playback.state.value.playing)
            withContext(Dispatchers.Main) {
                assertEquals(android.net.Uri.fromFile(file),player.currentMediaItem!!.localConfiguration!!.uri)
            }
            compose.onNodeWithText("Clear queue").performScrollTo().performClick()
            compose.onNodeWithText("Cancel").performClick()
            assertEquals(3,graph.playback.state.value.queue.size)
            assertTrue(graph.playback.state.value.playing)
            compose.onNodeWithText("Clear queue").performScrollTo().performClick()
            androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            compose.onNodeWithText("Clear queue and stop playback?").assertDoesNotExist()
            assertEquals(3,graph.playback.state.value.queue.size)
            // Paused seek immediately followed by clear must retain the final position.
            graph.playback.pause();graph.playback.seek(4567)
            val savedDuration=dao.episode(ids[0])!!.durationMs
            compose.onNodeWithText("Clear queue").performScrollTo().performClick()
            compose.onNodeWithText("Clear and stop").performClick()
            awaitQueue(emptyList())
            withTimeout(10000) { while(dao.episode(ids[0])!!.positionMs!=4567L) delay(50) }
            assertEquals(savedDuration,dao.episode(ids[0])!!.durationMs)
            assertEquals("downloaded",dao.episode(ids[0])!!.downloadState)
            assertTrue(file.exists())
            assertNull(dao.playerSession()?.episodeId)
            withContext(Dispatchers.Main) { assertFalse(player.playWhenReady) }
            compose.onNodeWithText("Your queue is clear").assertIsDisplayed()
            // Single item and clear during actual playback retain the latest progress as well.
            graph.playback.play(ids[1],2000)
            withTimeout(10000) { graph.playback.state.first { it.playing && it.queue==listOf(ids[1]) } }
            compose.onNodeWithTag("queue-list").performScrollToNode(hasContentDescription("Move Queue item 1 up"))
            compose.onNodeWithContentDescription("Move Queue item 1 up").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Move Queue item 1 down").assertIsNotEnabled()
            val before=withContext(Dispatchers.Main) { player.currentPosition }
            graph.playback.clear()
            awaitQueue(emptyList())
            withTimeout(10000) { while(dao.episode(ids[1])!!.positionMs<before) delay(50) }
            assertTrue(file.exists())
            withContext(Dispatchers.Main) { assertFalse(player.playWhenReady) }
            graph.playback.enqueue(ids[2]);awaitQueue(listOf(ids[2]))
            withContext(Dispatchers.Main) { assertFalse(player.playWhenReady);assertFalse(player.isPlaying) }
            graph.playback.clear();awaitQueue(emptyList())
            // Removing current with a successor retains the removed episode, not the successor position.
            graph.playback.play(ids[0],1000);graph.playback.pause();graph.playback.enqueue(ids[2])
            awaitQueue(listOf(ids[0],ids[2]))
            graph.playback.seek(3456);graph.playback.remove(ids[0])
            awaitQueue(listOf(ids[2]))
            withTimeout(10000) { while(dao.episode(ids[0])!!.positionMs!=3456L) delay(50) }
            assertFalse(dao.episode(ids[0])!!.completed)
            assertEquals(ids[2],graph.playback.state.value.episodeId)
        } finally {
            graph.playback.pause();graph.playback.clear()
            awaitQueue(emptyList())
            graph.db.withTransaction {
                dao.deletePodcast(tag);dao.clearQueue();dao.insertQueue(oldQueue)
                if(oldSession!=null)dao.putPlayerSession(oldSession)
            }
            graph.settings.flag("transcribeOnPlayback",oldTranscribeOnPlayback)
            file.delete()
        }
    }
}
