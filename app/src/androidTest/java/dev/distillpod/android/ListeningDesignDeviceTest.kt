package dev.distillpod.android

import android.content.ComponentName
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.distillpod.android.data.*
import dev.distillpod.android.features.*
import dev.distillpod.android.playback.mediaItem
import dev.distillpod.core.PcmWaveWriter
import dev.distillpod.core.readWhisperWave
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Exercises real UI actions with local fixtures; no account/provider requests. */
@RunWith(AndroidJUnit4::class)
class ListeningDesignDeviceTest {
    @get:Rule val compose = createQueuedAndroidComposeRule<MainActivity>()

    @Test fun listeningNavigationQueueAndLargeTextPreservePlaybackAndOwnerLibrary() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph = compose.activity.applicationContext.appGraph
        graph.ready.await()
        val dao = graph.db.library()
        val vm = withContext(Dispatchers.Main) { ViewModelProvider(compose.activity, AppViewModel.factory(graph))[AppViewModel::class.java] }
        val future = withContext(Dispatchers.Main) { MediaController.Builder(graph.context, SessionToken(graph.context, ComponentName(graph.context, PlaybackService::class.java))).buildAsync() }
        val c = withContext(Dispatchers.IO) { future.get(15, TimeUnit.SECONDS) }
        val directory = File(graph.context.filesDir, "validation").apply { mkdirs() }
        val backup = File(directory, "ui-original.json")
        check(!backup.exists()) { "Recover the private UI playback snapshot before another run" }
        delay(1500)
        val original = withContext(Dispatchers.Main) {
            JSONObject().put("queue", JSONArray((0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }))
                .put("current", c.currentMediaItem?.mediaId ?: "").put("position", c.currentPosition.coerceAtLeast(0))
                .put("speed", c.playbackParameters.speed.toDouble()).put("volume", c.volume.toDouble())
        }
        backup.writeText(original.toString())
        val id = "ui-design-${UUID.randomUUID()}"
        val source = File.createTempFile("ui-audio-", ".wav", graph.context.cacheDir)
        val ids = (0..2).map { "$id-$it" }
        val titles = listOf("Design validation episode", "Design next one", "Design next two")
        fun screenshot(name: String) { UiDevice.getInstance(instrumentation).takeScreenshot(File(directory, "ui-$name.png")) }
        try {
            val samples = readWhisperWave(instrumentation.context.assets.open("jfk.wav").use { it.readBytes() })
            val wave = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { wave.putShort((it * 32768).toInt().coerceIn(-32768, 32767).toShort()) }
            PcmWaveWriter(source, 16000, 1).use { writer -> repeat(20) { writer.append(ByteBuffer.wrap(wave.array())) }; writer.finish() }
            dao.putPodcast(PodcastEntity(id, "https://example.test/$id", "Design validation show"))
            ids.forEachIndexed { index, epId -> dao.putEpisode(EpisodeEntity(epId, id, epId, titles[index], "https://example.test/$epId", publishedAt = System.currentTimeMillis() + index,
                durationMs = 220000, localFile = source.absolutePath, downloadState = "downloaded", downloadedBytes = source.length(), totalBytes = source.length(), mediaFingerprint = id)) }
            dao.putTranscript(TranscriptEntity(ids[0], "ui", id, "fixture", "en", state = "ready", durationMs = 220000, processedMs = 220000))
            dao.putDistillation(DistillationEntity(id, ids[0], 5000, 65000, "Useful ideas deserve a second listen.", quote = "Useful ideas deserve a second listen.", insight = "Keep the original source with the saved idea.", state = "succeeded", fingerprint = id))
            withContext(Dispatchers.Main) {
                c.pause(); c.setMediaItems(ids.mapIndexed { index, epId -> mediaItem(EpisodeEntity(epId, id, epId, titles[index], "https://example.test/$epId", localFile = source.absolutePath), null) }, 0, 45000)
                c.volume = 0f; c.setPlaybackSpeed(1f); c.prepare(); vm.filter("all"); vm.root("library")
            }
            await { c.currentMediaItem?.mediaId == ids[0] && c.playbackState == Player.STATE_READY }
            compose.waitUntil(10000) { compose.onAllNodesWithText("Continue listening").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Continue listening").assertIsDisplayed()
            compose.onNodeWithContentDescription("Resume listening").performClick()
            await { c.isPlaying }
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Pause listening").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Pause listening").performClick()
            await { !c.isPlaying }
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Resume listening").fetchSemanticsNodes().isNotEmpty() }
            screenshot("library")
            // #146: the card follows the mini-player's episode even when that episode is marked played,
            // instead of falling back to another in-progress episode.
            dao.markCompleted(ids[0], true)
            compose.waitUntil(5000) { vm.library.value?.resume?.let { it.id == ids[0] && it.completed } == true }
            compose.onNode(hasTestTag("resume-card") and hasText(titles[0])).assertIsDisplayed()
            dao.markCompleted(ids[0], false)
            compose.waitUntil(5000) { vm.episodes.value.firstOrNull { it.id == ids[0] }?.completed == false }
            withContext(Dispatchers.Main) { c.seekTo(45000) }
            compose.onNodeWithContentDescription("Open now playing").performClick()
            compose.onNodeWithContentDescription("Back 10 seconds").performClick()
            await { c.currentPosition in 34900..35100 }
            compose.onNodeWithContentDescription("Forward 30 seconds").performClick()
            await { c.currentPosition in 64900..65100 }
            compose.onNodeWithText("1.25×").performClick()
            await { c.playbackParameters.speed == 1.25f }
            compose.onNodeWithContentDescription("Episode playback position").assertIsDisplayed()
            assertTrue("The full player gives its controls the bottom navigation space", compose.onAllNodesWithText("Library").fetchSemanticsNodes().isEmpty())
            // Show navigation is pure navigation: preserve the player and its queue.
            val beforeShow = withContext(Dispatchers.Main) { c.currentPosition }
            val queueBeforeShow = withContext(Dispatchers.Main) { (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId } }
            repeat(2) {
                compose.onNodeWithContentDescription("View show: Design validation show").performScrollTo().assertHasClickAction().performClick()
                compose.waitUntil(5000) { vm.backStack.value.last() == "podcast:$id" }
                // The same show name is also present in the clickable mini-player.
                compose.onNode(hasText("Design validation show") and !hasClickAction() and
                    !hasAnyAncestor(hasClickAction())).assertIsDisplayed()
                withContext(Dispatchers.Main) {
                    assertEquals(beforeShow, c.currentPosition)
                    assertEquals(queueBeforeShow, (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId })
                    assertEquals(ids[0], c.currentMediaItem?.mediaId)
                    assertEquals(1.25f, c.playbackParameters.speed)
                    assertFalse(c.isPlaying)
                }
                if (it == 0) compose.onNodeWithContentDescription("Back").performClick()
                else UiDevice.getInstance(instrumentation).pressBack()
                compose.waitUntil(5000) { vm.backStack.value.last() == "player" }
            }
            // Selecting a different episode must navigate to that episode's show,
            // not the currently playing show's destination.
            val otherShow = "$id-other"
            dao.putPodcast(PodcastEntity(otherShow, "https://example.test/$otherShow", "Other selected show"))
            dao.putEpisode(EpisodeEntity(otherShow, otherShow, otherShow, "Other selected episode", "https://example.test/other"))
            try {
                compose.waitUntil(5000) { vm.episodes.value.any { it.id == otherShow } && vm.podcasts.value.any { it.id == otherShow } }
                withContext(Dispatchers.Main) { c.play(); vm.open("episode:$otherShow") }
                await { c.isPlaying }
                val playingPosition = withContext(Dispatchers.Main) { c.currentPosition }
                compose.onNodeWithContentDescription("View show: Other selected show").assertHasClickAction().performClick()
                compose.waitUntil(5000) { vm.backStack.value.last() == "podcast:$otherShow" }
                withContext(Dispatchers.Main) {
                    assertTrue(c.isPlaying)
                    assertTrue(c.currentPosition >= playingPosition)
                    assertEquals(ids[0], c.currentMediaItem?.mediaId)
                    assertEquals(queueBeforeShow, (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId })
                }
                compose.onNodeWithContentDescription("Back").performClick()
                compose.waitUntil(5000) { vm.backStack.value.last() == "episode:$otherShow" }
                compose.onNodeWithText("Other selected episode").assertIsDisplayed()
                withContext(Dispatchers.Main) { c.pause(); c.seekTo(beforeShow); vm.back() }
            } finally { dao.deletePodcast(otherShow) }
            screenshot("player")
            compose.onNodeWithContentDescription("Open queue").performClick()
            compose.onNodeWithText("Playback order").assertIsDisplayed()
            // The helper scrolls first: a small emulator may not have composed the last row yet.
            compose.selectQueueMenuAction(titles[2], "Move up", disabled = listOf("Move down"))
            await { (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId } == listOf(ids[0], ids[2], ids[1]) }
            compose.waitUntil(5000) { vm.player.value.queue == listOf(ids[0], ids[2], ids[1]) }
            assertEquals(65000L, withContext(Dispatchers.Main) { c.currentPosition })
            screenshot("queue")
            compose.onNodeWithText("Saved", useUnmergedTree = true).performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Useful ideas deserve a second listen.", substring = true))
            screenshot("saved")
            compose.onNodeWithText("Useful ideas deserve a second listen.", substring = true).performClick()
            compose.onNodeWithText("Replay 0:05").performScrollTo().assertIsDisplayed()
            screenshot("moment")
            withContext(Dispatchers.Main) {
                vm.open("player")
                compose.activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(graph.context.resources.displayMetrics.density, 1.6f)) { ProductApp(vm) }
                }
            }
            compose.onNodeWithContentDescription("View show: Design validation show").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("Back 10 seconds").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Distill last 60 seconds").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Summary & chapters").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Ask episode").performScrollTo().assertIsDisplayed()
            screenshot("large-text")
            withContext(Dispatchers.Main) { c.seekTo(1, 0); vm.root("library"); vm.open("podcast:$id"); vm.filter("progress") }
            await { c.currentMediaItem?.mediaId == ids[2] }
            delay(1500)
            dao.markCompleted(ids[0], true)
            compose.waitUntil(10000) { vm.episodes.value.firstOrNull { it.id == ids[0] }?.completed == true }
            // Show controls and large text can put this lazy item outside the viewport.
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Nothing in progress"))
            compose.onNodeWithText("Show latest").performScrollTo().performClick()
            // Changing the filter launches a scroll-to-top effect. Do not race that reset with
            // our own scroll, or let Compose's failure formatter hide an ambiguous selector.
            var scrollContainers = -1
            var scrollPosition: Float? = null
            try {
                compose.waitUntil(10000) {
                    val latestReady = vm.filter.value == "all" &&
                        vm.backStack.value.lastOrNull() == "podcast:$id" &&
                        vm.episodes.value.any { it.id == ids[0] && it.podcastId == id }
                    if (!latestReady) false else {
                        val containers = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes()
                        scrollContainers = containers.size
                        scrollPosition = if (containers.size == 1) compose.runOnUiThread {
                            containers.single().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke()
                        } else null
                        scrollContainers == 1 && scrollPosition == 0f
                    }
                }
            } catch (timeout: ComposeTimeoutException) {
                throw AssertionError("Show latest did not settle: filter=${vm.filter.value}, " +
                    "expectedRoute=${vm.backStack.value.lastOrNull() == "podcast:$id"}, " +
                    "fixturePresent=${vm.episodes.value.any { it.id == ids[0] }}, " +
                    "scrollContainers=$scrollContainers, scrollPosition=$scrollPosition", timeout)
            }
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(titles[0]))
            compose.onNodeWithText(titles[0]).assertIsDisplayed()
            // #146: a failed refresh names the reason on its own show page, with a retry.
            dao.putPodcast(dao.podcast(id)!!.copy(error = "Couldn't reach the feed. Check your connection and try again."))
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("This show couldn't refresh"))
            compose.onNodeWithText("Couldn't reach the feed. Check your connection and try again.").assertIsDisplayed()
            compose.onNodeWithContentDescription("Retry Design validation show").assertIsDisplayed()
            screenshot("show-feed-error")
        } finally {
            val originalIds = original.getJSONArray("queue").let { a -> (0 until a.length()).map { a.getString(it) } }
            val episodes = originalIds.mapNotNull { dao.episode(it) }
            val podcasts = dao.podcasts().associateBy { it.id }
            withContext(Dispatchers.Main) {
                c.pause()
                if (episodes.isEmpty()) c.clearMediaItems() else {
                    val index = episodes.indexOfFirst { it.id == original.getString("current") }.coerceAtLeast(0)
                    c.setMediaItems(episodes.map { mediaItem(it, podcasts[it.podcastId]) }, index, original.getLong("position")); c.prepare()
                }
                c.volume = original.getDouble("volume").toFloat(); c.setPlaybackSpeed(original.getDouble("speed").toFloat())
                vm.filter("all"); vm.root("library")
            }
            withTimeout(10000) { while (graph.activeFiles.busy(source.absolutePath)) delay(100) }
            dao.deletePodcast(id); source.delete(); delay(1500)
            assertEquals(originalIds, dao.queue().map { it.episodeId })
            backup.delete()
            withContext(Dispatchers.Main) { c.release() }
        }
    }

    private suspend fun await(predicate: () -> Boolean) = withTimeout(15000) { while (!withContext(Dispatchers.Main) { predicate() }) delay(100) }
}
