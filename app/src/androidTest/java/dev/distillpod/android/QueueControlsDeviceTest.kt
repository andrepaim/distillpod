package dev.distillpod.android

import android.os.Build
import android.os.Looper
import androidx.media3.common.Player
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
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
        fun playbackSummary(): String = graph.playback.state.value.let {
            "queueIndices=${it.queue.map(ids::indexOf)}, currentIndex=${ids.indexOf(it.episodeId)}, positionMs=${it.positionMs}, playing=${it.playing}"
        }
        suspend fun <T> phase(name:String, observed:()->String = { "" }, block:suspend ()->T):T =
            try { block() } catch (failure:Throwable) {
                throw AssertionError("Queue acceptance phase '$name' failed; ${observed()}; ${playbackSummary()}",failure)
            }
        suspend fun awaitQueue(expected:List<String>) {
            phase("controller queue ${expected.map(ids::indexOf)}") {
                withTimeout(15000) { graph.playback.state.first { it.queue==expected } }
            }
            var persisted=emptyList<String>()
            phase("persisted queue ${expected.map(ids::indexOf)}", { "persistedIndices=${persisted.map(ids::indexOf)}" }) {
                withTimeout(15000) {
                    do { persisted=dao.queue().map { it.episodeId };if(persisted!=expected)delay(50) } while(persisted!=expected)
                }
            }
        }
        suspend fun awaitProgress(index:Int, expected:String, accepts:(Long)->Boolean) {
            var observed=-1L
            phase("persisted progress item $index expected $expected", { "persistedPositionMs=$observed" }) {
                withTimeout(10000) {
                    do { observed=dao.episode(ids[index])!!.positionMs;if(!accepts(observed))delay(50) } while(!accepts(observed))
                }
            }
        }
        fun openClearDialog() {
            // Lazy headers may be disposed when a moved item anchors the scroll position.
            compose.onNodeWithTag("queue-list").performScrollToNode(hasText("Clear queue"))
            compose.onNodeWithText("Clear queue").performClick()
            compose.onNodeWithText("Clear queue and stop playback?").assertIsDisplayed()
        }
        var primaryFailure:Throwable?=null
        try {
            graph.settings.flag("transcribeOnPlayback",false)
            phase("automatic transcription disabled") { withTimeout(10000) { graph.preferences.first { !it.transcribeOnPlayback } } }
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
            phase("queue controls visible") {
                compose.waitUntil(15000) { compose.onAllNodesWithText("Clear queue").fetchSemanticsNodes().isNotEmpty() }
            }
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
            phase("playing current item") { withTimeout(10000) { graph.playback.state.first { it.playing } } }
            graph.playback.move(ids[0],1)
            awaitQueue(listOf(ids[2],ids[1],ids[0]))
            assertEquals(ids[0],graph.playback.state.value.episodeId)
            assertTrue(graph.playback.state.value.playing)
            withContext(Dispatchers.Main) {
                assertEquals(android.net.Uri.fromFile(file),player.currentMediaItem!!.localConfiguration!!.uri)
            }
            openClearDialog()
            compose.onNodeWithText("Cancel").performClick()
            assertEquals(3,graph.playback.state.value.queue.size)
            assertTrue(graph.playback.state.value.playing)
            openClearDialog()
            val device=androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            // Wait for the dialog's actual Android window before injecting an OS Back event.
            phase("confirmation Android window visible") {
                assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(
                    androidx.test.uiautomator.By.text("Clear queue and stop playback?")),5000))
            }
            device.pressBack()
            phase("confirmation dismissed by Back") {
                compose.waitUntil(5000) {
                    compose.onAllNodesWithText("Clear queue and stop playback?").fetchSemanticsNodes().isEmpty()
                }
            }
            assertEquals("queue",vm.backStack.value.last())
            compose.onNodeWithTag("queue-list").assertExists()
            assertEquals(3,graph.playback.state.value.queue.size)
            // Paused seek immediately followed by clear must retain the final position.
            graph.playback.pause();graph.playback.seek(4567)
            val savedDuration=dao.episode(ids[0])!!.durationMs
            openClearDialog()
            compose.onNodeWithText("Clear and stop").performClick()
            awaitQueue(emptyList())
            awaitProgress(0,"4567") { it==4567L }
            assertEquals(savedDuration,dao.episode(ids[0])!!.durationMs)
            assertEquals("downloaded",dao.episode(ids[0])!!.downloadState)
            assertTrue(file.exists())
            assertNull(dao.playerSession()?.episodeId)
            withContext(Dispatchers.Main) { assertFalse(player.playWhenReady) }
            compose.onNodeWithText("Your queue is clear").assertIsDisplayed()
            // Single item and clear during actual playback retain the latest progress as well.
            graph.playback.play(ids[1],2000)
            phase("playing single item") { withTimeout(10000) { graph.playback.state.first { it.playing && it.queue==listOf(ids[1]) } } }
            compose.onNodeWithTag("queue-list").performScrollToNode(hasContentDescription("Move Queue item 1 up"))
            compose.onNodeWithContentDescription("Move Queue item 1 up").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Move Queue item 1 down").assertIsNotEnabled()
            // MediaController extrapolates positions and masks REMOVE locally before the server
            // handles it. Observe the actual service ExoPlayer, not that predicted callback.
            val removed=CompletableDeferred<Long>()
            val removalListener=object : Player.Listener {
                override fun onPositionDiscontinuity(oldPosition:Player.PositionInfo,newPosition:Player.PositionInfo,reason:Int) {
                    if(reason==Player.DISCONTINUITY_REASON_REMOVE && oldPosition.mediaItem?.mediaId==ids[1] && newPosition.mediaItem==null) {
                        removed.complete(oldPosition.positionMs)
                    }
                }
            }
            val servicePlayer=withContext(Dispatchers.Main) { fixtureServicePlayer(ids[1]) }
            var before=-1L
            var expected=-1L
            var persisted=-1L
            try {
                withContext(Dispatchers.Main) {
                    assertSame(Looper.myLooper(),servicePlayer.applicationLooper)
                    assertTrue(servicePlayer.isPlaying)
                    before=servicePlayer.currentPosition
                    assertTrue(before>0)
                    servicePlayer.addListener(removalListener)
                }
                graph.playback.clear()
                awaitQueue(emptyList())
                phase("persisted playing removal event", { "serviceBeforeMs=$before, removalPositionMs=$expected, persistedPositionMs=$persisted" }) {
                    withTimeout(10000) {
                        expected=removed.await()
                        assertTrue("Service removal must retain its own observed progress",expected>=before)
                        do { persisted=dao.episode(ids[1])!!.positionMs;if(persisted!=expected)delay(50) } while(persisted!=expected)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { servicePlayer.removeListener(removalListener) }
            }
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
            awaitProgress(0,"3456") { it==3456L }
            assertFalse(dao.episode(ids[0])!!.completed)
            assertEquals(ids[2],graph.playback.state.value.episodeId)
        } catch (failure:Throwable) {
            primaryFailure=failure
            throw failure
        } finally {
            try {
                phase("cleanup restore fixture") {
                    graph.playback.pause();graph.playback.clear()
                    awaitQueue(emptyList())
                    graph.db.withTransaction {
                        dao.deletePodcast(tag);dao.clearQueue();dao.insertQueue(oldQueue)
                        if(oldSession!=null)dao.putPlayerSession(oldSession)
                    }
                    graph.settings.flag("transcribeOnPlayback",oldTranscribeOnPlayback)
                    file.delete()
                }
            } catch (cleanupFailure:Throwable) {
                val original=primaryFailure
                if(original!=null) original.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
    }



}
