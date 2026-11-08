package dev.distillpod.android

import android.graphics.SurfaceTexture
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.Surface
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.*
import dev.distillpod.android.playback.mediaItem
import dev.distillpod.android.video.VideocastMediaSourceFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Deliberate owner acceptance only. Ordinary emulator suites must never select this class. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OwnerVideocastDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.appGraph
    private val dao get() = graph.db.library()
    private val id = "validation-owner-videocast"
    private val backup get() = File(context.noBackupFilesDir, "validation/videocast-owner.json")
    private suspend fun player() = graph.playback.sessionPlayer() as MediaController
    private val playlist = "https://www.youtube.com/playlist?list=PLaE_mZALZ0V3lAEAvIfZC-aKf2ATIY3uy"

    @Test fun explicitOwnerAcceptance() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allowOwnerVideocast") == "true")
        check(android.os.Build.MODEL == "SM-S908E") { "Owner acceptance is restricted to the authorized S22 model" }
        graph.ready.await()
        when (args.getString("videocastPhase")) {
            "live" -> {
                unmetered(); snapshot()
                try {
                    val feed = graph.youtube.feed(id, playlist)
                    val urls = feed.episodes.take(2).map { it.audioUrl } + "https://www.youtube.com/watch?v=jNQXAC9IVRw"
                    check(urls.size == 3)
                    for (url in urls) { graph.youtube.invalidate(url); decode(url) }
                    fixture(feed.episodes.first())
                    serviceModes()
                    receipt("live", JSONObject().put("decodedVideos", 3).put("serviceModes", true))
                } finally { restore() }
            }
            "download" -> {
                unmetered(); snapshot()
                try {
                    val episode = graph.youtube.feed(id, playlist).episodes.first()
                    fixture(episode)
                    val file = withTimeout(15 * 60_000L) { graph.media.ensureDownloaded(id) }
                    val extractor = android.media.MediaExtractor()
                    val duration = try {
                        extractor.setDataSource(file.absolutePath)
                        (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                            .filter { it.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                            .maxOf { it.getLong(android.media.MediaFormat.KEY_DURATION) / 1000 }
                    } finally { extractor.release() }
                    assertTrue(duration > 60_000)
                    assertEquals(file.length(), dao.episode(id)!!.downloadedBytes)
                    assertEquals(episode.audioUrl, dao.episode(id)!!.audioUrl)
                    receipt("download", JSONObject().put("bytes", file.length()).put("durationMs", duration))
                } catch (e: Exception) { restore(); throw e }
            }
            "queue" -> {
                unmetered(); snapshot()
                try {
                    val episode = graph.youtube.feed(id, playlist).episodes.first()
                    fixture(episode)
                    val next = "$id-next"
                    dao.putEpisode(EpisodeEntity(next, id, next, "Public queue acceptance", episode.audioUrl,
                        durationMs = episode.durationMs ?: 0, mimeType = "video/youtube", mediaFingerprint = "validation"))
                    graph.playback.play(id, 5_000); await { it.isPlaying }
                    graph.playback.video(id, true)
                    graph.playback.enqueue(next, next = true)
                    await { it.currentMediaItem?.mediaId == id && it.currentMediaItem?.localConfiguration?.uri?.getQueryParameter("distillpod_video") == "1" }
                    val c = player()
                    withContext(Dispatchers.Main) {
                        assertEquals(next, c.getMediaItemAt(c.currentMediaItemIndex + 1).mediaId)
                        c.seekToNextMediaItem()
                    }
                    await { it.currentMediaItem?.mediaId == next && C.TRACK_TYPE_VIDEO in it.trackSelectionParameters.disabledTrackTypes && it.playbackState == Player.STATE_READY }
                    graph.playback.video(next, true); graph.playback.leaveVideo(id); delay(300)
                    withContext(Dispatchers.Main) { assertFalse(C.TRACK_TYPE_VIDEO in c.trackSelectionParameters.disabledTrackTypes) }
                    receipt("queue", JSONObject().put("selectionPreserved", true).put("nextStartsInAudio", true).put("obsoleteCleanupIgnored", true))
                } finally { restore() }
            }
            "ui_background" -> {
                check(!context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {
                    "The owner must unlock the phone before the foreground UI check"
                }
                unmetered(); snapshot()
                try {
                    fixture(graph.youtube.feed(id, playlist).episodes.first())
                    graph.playback.play(id, 5_000); await { it.isPlaying }
                    graph.playback.pause(); graph.playback.speed(1.25f)
                    await { !it.isPlaying && it.playbackState == Player.STATE_READY }
                    val c = player()
                    val position = withContext(Dispatchers.Main) { c.currentPosition }
                    context.startActivity(android.content.Intent(context, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("openPlayer", true))
                    val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
                    val chip = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("Video").enabled(true)), 15_000)
                    assertNotNull("Video control must be visible in the actual player", chip)
                    chip!!.click()
                    await { C.TRACK_TYPE_VIDEO !in it.trackSelectionParameters.disabledTrackTypes && it.playbackState == Player.STATE_READY }
                    assertTrue(device.pressHome())
                    await { C.TRACK_TYPE_VIDEO in it.trackSelectionParameters.disabledTrackTypes &&
                        it.currentMediaItem?.localConfiguration?.uri?.getQueryParameter("distillpod_video") == null && it.playbackState == Player.STATE_READY }
                    withContext(Dispatchers.Main) {
                        assertFalse(c.playWhenReady); assertEquals(position, c.currentPosition)
                        assertEquals(1.25f, c.playbackParameters.speed, 0.001f)
                    }
                    // Repeat while the public extractor is still pending, not only after READY.
                    context.startActivity(android.content.Intent(context, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("openPlayer", true))
                    val nextChip = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("Video").enabled(true)), 15_000)
                    assertNotNull(nextChip)
                    graph.youtube.invalidate(dao.episode(id)!!.audioUrl)
                    nextChip!!.click()
                    assertTrue("Exercise the loading state before backgrounding", device.wait(
                        androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Loading…")), 5_000))
                    assertTrue(device.pressHome())
                    // Beyond the extraction deadline: a late result must never re-enable video.
                    delay(45_000)
                    withContext(Dispatchers.Main) {
                        assertTrue(C.TRACK_TYPE_VIDEO in c.trackSelectionParameters.disabledTrackTypes)
                        assertNull(c.currentMediaItem?.localConfiguration?.uri?.getQueryParameter("distillpod_video"))
                        assertFalse(c.playWhenReady); assertEquals(position, c.currentPosition)
                        assertEquals(1.25f, c.playbackParameters.speed, 0.001f)
                    }
                    receipt("ui_background", JSONObject().put("homeRestoresAudio", true).put("pendingVideoStaysCancelled", true)
                        .put("pausedPositionAndSpeedPreserved", true))
                } finally { restore() }
            }
            "offline" -> {
                check(backup.isFile)
                check(context.getSystemService(ConnectivityManager::class.java).activeNetwork == null) { "Host must disconnect networking for offline acceptance" }
                graph.playback.play(id, 5_000)
                await { it.isPlaying && it.currentPosition >= 5_500 }
                assertEquals("file", withContext(Dispatchers.Main) { player().currentMediaItem!!.localConfiguration!!.uri.scheme })
                graph.playback.pause()
                receipt("offline", JSONObject().put("localPlayback", true))
            }
            "transcribe" -> {
                check(backup.isFile); unmetered()
                val prefs = graph.settings.flow.first()
                check(dao.model(prefs.model)?.state == "ready") { "The selected local model must be installed before this test" }
                graph.transcription.prepare(id, forceLocal = true, allowOnline = false)
                withTimeout(30 * 60_000L) {
                    while (dao.transcript(id)?.state !in setOf("ready", "failed", "waiting_model")) delay(1_000)
                }
                val transcript = dao.transcript(id)!!
                assertEquals(transcript.error, "ready", transcript.state)
                assertEquals("whisper", transcript.source)
                assertTrue(transcript.durationMs > 60_000)
                assertEquals(transcript.durationMs, transcript.processedMs)
                assertTrue(dao.segments(id, transcript.revision).isNotEmpty())
                receipt("transcribe", JSONObject().put("ready", true).put("segments", dao.segments(id, transcript.revision).size))
            }
            "verify_transcript" -> {
                check(backup.isFile)
                val transcript = requireNotNull(dao.transcript(id))
                assertEquals("ready", transcript.state); assertEquals("whisper", transcript.source)
                assertTrue(transcript.durationMs > 60_000); assertEquals(transcript.durationMs, transcript.processedMs)
                val covered = dao.coverage(id, transcript.revision).sumOf { it.endMs - it.startMs }
                assertTrue("Whole-episode coverage is required", covered >= transcript.durationMs - 1_000)
                receipt("verify_transcript", JSONObject().put("durationMs", transcript.durationMs).put("processedMs", transcript.processedMs).put("coveredMs", covered))
            }
            "restore" -> { restore(); receipt("restore", JSONObject().put("restored", true)) }
            else -> error("Select an explicit videocast phase")
        }
    }

    private fun unmetered() {
        val network = context.getSystemService(ConnectivityManager::class.java)
        check(network.getNetworkCapabilities(network.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true)
    }
    private suspend fun snapshot() {
        check(!backup.exists()) { "Restore the preceding owner trial first" }
        check(dao.episode(id) == null && dao.podcast(id) == null) { "Owner fixture ID is already in use" }
        val c = player(); delay(1_500)
        val prefs = graph.settings.flow.first()
        val json = withContext(Dispatchers.Main) {
            JSONObject().put("queue", JSONArray((0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }))
                .put("current", c.currentMediaItem?.mediaId ?: "").put("position", c.currentPosition.coerceAtLeast(0))
                .put("speed", c.playbackParameters.speed.toDouble()).put("volume", c.volume.toDouble())
                .put("automatic", prefs.transcribeOnPlayback)
        }
        backup.parentFile!!.mkdirs()
        val atomic = android.util.AtomicFile(backup); val stream = atomic.startWrite()
        try { stream.write(json.toString().toByteArray()); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
        graph.settings.flag("transcribeOnPlayback", false)
        graph.playback.pause()
    }
    private suspend fun fixture(episode: dev.distillpod.core.FeedEpisode) {
        dao.putPodcast(PodcastEntity(id, "https://validation.invalid/$id", "Videocast acceptance", subscribed = false))
        dao.putEpisode(EpisodeEntity(id, id, id, "Public videocast acceptance", episode.audioUrl,
            durationMs = episode.durationMs ?: 0, mimeType = "video/youtube", mediaFingerprint = "validation"))
    }
    private suspend fun serviceModes() {
        graph.playback.play(id, 5_000); await { it.isPlaying }
        graph.playback.pause(); graph.playback.speed(1.25f); await { !it.isPlaying && it.playbackState == Player.STATE_READY }
        val c = player(); val position = withContext(Dispatchers.Main) { c.currentPosition }
        val trace = JSONArray()
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (trace.length() < 100) trace.put(JSONObject().put("position", p.currentPosition).put("state", p.playbackState)
                    .put("videoDisabled", C.TRACK_TYPE_VIDEO in p.trackSelectionParameters.disabledTrackTypes)
                    .put("marker", p.currentMediaItem?.localConfiguration?.uri?.getQueryParameter("distillpod_video"))
                    .put("errorCode", p.playerError?.errorCode ?: 0))
            }
        }
        withContext(Dispatchers.Main) { c.addListener(listener) }
        try {
        graph.playback.video(id, true)
        await { C.TRACK_TYPE_VIDEO !in it.trackSelectionParameters.disabledTrackTypes && it.playbackState == Player.STATE_READY }
        withContext(Dispatchers.Main) { assertFalse(c.playWhenReady); assertEquals(position, c.currentPosition); assertEquals(1.25f, c.playbackParameters.speed) }
        graph.playback.video(id, false)
        await { C.TRACK_TYPE_VIDEO in it.trackSelectionParameters.disabledTrackTypes && it.playbackState == Player.STATE_READY }
        delay(1_000)
        withContext(Dispatchers.Main) { assertEquals(position, c.currentPosition) }
        graph.playback.seek(20_000); graph.playback.toggle(); await { it.isPlaying && it.currentPosition >= 20_500 }
        graph.playback.pause()
        } finally {
            withContext(Dispatchers.Main) { c.removeListener(listener) }
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("videocast_trace", trace.toString()) })
        }
    }
    private suspend fun decode(url: String) = withContext(Dispatchers.IO) {
        val rendered = CountDownLatch(1); val failure = AtomicReference<PlaybackException?>()
        lateinit var p: ExoPlayer; lateinit var texture: SurfaceTexture; lateinit var surface: Surface
        instrumentation.runOnMainSync {
            texture = SurfaceTexture(false).apply { setDefaultBufferSize(1280, 720) }; surface = Surface(texture)
            p = ExoPlayer.Builder(context).setMediaSourceFactory(VideocastMediaSourceFactory(context, graph.youtube)).build()
            p.volume = 0f; p.setVideoSurface(surface)
            p.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { rendered.countDown() }
                override fun onPlayerError(error: PlaybackException) { failure.set(error); rendered.countDown() }
            })
            p.setMediaItem(MediaItem.fromUri("$url&distillpod_video=1")); p.prepare(); p.play()
        }
        try {
            assertTrue("Public video did not render", rendered.await(90, TimeUnit.SECONDS)); assertNull("Public video failed", failure.get())
            instrumentation.runOnMainSync { assertNotNull(p.videoFormat); assertNotNull(p.audioFormat) }
        } finally { instrumentation.runOnMainSync { p.release(); surface.release(); texture.release() } }
    }
    private suspend fun await(predicate: (Player) -> Boolean) {
        val c = player()
        withTimeout(90_000) { while (!withContext(Dispatchers.Main) { predicate(c) }) delay(100) }
    }
    private suspend fun restore() = withContext(NonCancellable) {
        check(backup.isFile) { "No owner backup exists" }
        val old = JSONObject(backup.readText()); val c = player()
        graph.transcription.pause(id)
        val ids = old.getJSONArray("queue").let { a -> (0 until a.length()).map { a.getString(it) } }
        val episodes = ids.map { requireNotNull(dao.episode(it)) }; val podcasts = dao.podcasts().associateBy { it.id }
        withContext(Dispatchers.Main) {
            c.pause(); c.clearMediaItems()
            if (episodes.isNotEmpty()) {
                val index = episodes.indexOfFirst { it.id == old.getString("current") }.coerceAtLeast(0)
                c.setMediaItems(episodes.map { mediaItem(it, podcasts[it.podcastId]) }, index, old.getLong("position")); c.prepare()
            }
            c.setPlaybackSpeed(old.getDouble("speed").toFloat()); c.volume = old.getDouble("volume").toFloat()
        }
        withTimeout(30_000) { while (dao.queue().map { it.episodeId } != ids || graph.activeFiles.busy(dao.episode(id)?.localFile.orEmpty())) delay(200) }
        graph.media.deleteDownload(id); dao.deletePodcast(id)
        graph.settings.flag("transcribeOnPlayback", old.getBoolean("automatic"))
        check(backup.delete()) { "Owner backup cleanup failed" }
    }
    private fun receipt(phase: String, fields: JSONObject) {
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("videocast_receipt", fields.put("phase", phase).toString()) })
    }
}
