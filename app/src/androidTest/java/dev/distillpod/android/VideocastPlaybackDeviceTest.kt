package dev.distillpod.android

import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.data.PodcastEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import dev.distillpod.android.playback.swapCurrentSource

/** Synthetic audio validates service/controller mode changes, not video decoding or YouTube. */
@RunWith(AndroidJUnit4::class)
class VideocastPlaybackDeviceTest {
    @Test fun modesPreservePausedPositionSpeedAndQueueAndDoNotLeakToAnotherEpisode() = runBlocking {
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph = instrumentation.targetContext.appGraph
        graph.ready.await()
        val tag = "videocast-fixture-${UUID.randomUUID()}"
        val file = File(graph.context.cacheDir, "$tag.wav")
        instrumentation.context.assets.open("jfk.wav").use { input -> file.outputStream().use(input::copyTo) }
        val dao = graph.db.library()
        val player = graph.playback.sessionPlayer()
        suspend fun awaitPlayer(predicate: (Player) -> Boolean) = withTimeout(10_000) {
            while (!withContext(Dispatchers.Main) { predicate(player) }) delay(50)
        }
        try {
            graph.playback.pause(); graph.playback.clear()
            withTimeout(10_000) { graph.playback.state.first { it.queue.isEmpty() } }
            dao.putPodcast(PodcastEntity(tag, "https://example.test/$tag", "Videocast fixture"))
            for (id in listOf("a", "b")) dao.putEpisode(EpisodeEntity("$tag-$id", tag, id, "Episode $id",
                "https://example.test/$id.mp4", mimeType = "video/mp4", localFile = file.absolutePath, downloadState = "downloaded"))
            graph.playback.play("$tag-a", 2_000)
            awaitPlayer { it.isPlaying }
            graph.playback.enqueue("$tag-b")
            graph.playback.pause(); graph.playback.speed(1.25f)
            awaitPlayer { !it.isPlaying && it.playbackState == Player.STATE_READY }
            val position = withContext(Dispatchers.Main) { player.currentPosition }
            // Exercise real controller/source replacement while earlier preparation is pending.
            // The plain mode toggle below does not replace this local fixture's media source.
            withContext(Dispatchers.Main) {
                val original = player.currentMediaItem!!
                repeat(4) { pass ->
                    val alternate = original.buildUpon().setUri(android.net.Uri.fromFile(file).buildUpon().appendQueryParameter("source", pass.toString()).build()).build()
                    swapCurrentSource(player, alternate, position)
                    swapCurrentSource(player, original, position)
                }
            }
            awaitPlayer { it.playbackState == Player.STATE_READY && !it.isPlaying }
            delay(300)
            withContext(Dispatchers.Main) {
                assertEquals(position, player.currentPosition)
                assertEquals(listOf("$tag-a", "$tag-b"), (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
            }
            graph.playback.video("$tag-a", true)
            awaitPlayer { C.TRACK_TYPE_VIDEO !in it.trackSelectionParameters.disabledTrackTypes }
            withContext(Dispatchers.Main) {
                assertFalse(player.playWhenReady)
                assertEquals(position, player.currentPosition)
                assertEquals(1.25f, player.playbackParameters.speed)
                assertEquals(listOf("$tag-a", "$tag-b"), (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
            }
            graph.playback.video("$tag-a", false)
            awaitPlayer { C.TRACK_TYPE_VIDEO in it.trackSelectionParameters.disabledTrackTypes }
            graph.playback.video("$tag-a", true)
            withContext(Dispatchers.Main) { player.seekToNextMediaItem() }
            awaitPlayer { it.currentMediaItem?.mediaId == "$tag-b" && C.TRACK_TYPE_VIDEO in it.trackSelectionParameters.disabledTrackTypes }
            graph.playback.video("$tag-b", true)
            graph.playback.leaveVideo("$tag-a")
            delay(300)
            withContext(Dispatchers.Main) { assertFalse(C.TRACK_TYPE_VIDEO in player.trackSelectionParameters.disabledTrackTypes) }
            graph.playback.video("$tag-b", false)
            awaitPlayer { C.TRACK_TYPE_VIDEO in it.trackSelectionParameters.disabledTrackTypes }
        } finally {
            graph.playback.pause(); graph.playback.clear(); graph.playback.speed(1f)
            withTimeout(10_000) { graph.playback.state.first { it.queue.isEmpty() } }
            delay(300)
            dao.deletePodcast(tag)
            file.delete()
        }
    }
}
