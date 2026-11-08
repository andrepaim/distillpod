package dev.distillpod.android

import android.content.ComponentName
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.os.Handler
import android.os.Looper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.*
import dev.distillpod.android.playback.mediaItem
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit manual phases; the actual hour runs after instrumentation exits. */
@RunWith(AndroidJUnit4::class)
class PlaybackReliabilityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.appGraph
    private val directory get() = File(context.filesDir, "validation").apply { mkdirs() }
    private val backup get() = File(directory, "playback-original.json")
    private val id = "validation-long-playback"

    private suspend fun controller(): MediaController {
        val future = withContext(Dispatchers.Main) {
            MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        }
        return withContext(Dispatchers.IO) { future.get(15, TimeUnit.SECONDS) }
    }

    @Test fun stageLockedTrial() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lockedTrial") == "stage")
        graph.ready.await()
        check(!backup.exists()) { "Restore the preceding trial before staging another" }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        check(connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true) {
            "Use an unmetered connection for the public long-episode fixture"
        }
        val source = JSONObject(instrumentation.context.assets.open("long-playback-source.json").bufferedReader().use { it.readText() })
        val dao = graph.db.library()
        dao.putPodcast(PodcastEntity(id, "https://validation.invalid/long-playback", "Playback validation", subscribed = false))
        dao.putEpisode(EpisodeEntity(id, id, id, "Long playback validation", source.getString("audio"),
            durationMs = (source.getDouble("durationSeconds") * 1000).toLong(), mediaFingerprint = "validation", publishedAt = System.currentTimeMillis()))
        val file = graph.media.ensureDownloaded(id)
        val extractor = MediaExtractor()
        val duration = try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                .maxOf { it.getLong(MediaFormat.KEY_DURATION) / 1000 }
        } finally { extractor.release() }
        assertTrue("Need more than 61 minutes of real audio", duration > 61 * 60_000L)
        val c = controller()
        try {
            // Allow the service's initial persisted queue restoration to finish.
            delay(1500)
            val automatic = graph.settings.flow.first().transcribeOnPlayback
            val original = withContext(Dispatchers.Main) {
                JSONObject().put("queue", JSONArray((0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }))
                    .put("current", c.currentMediaItem?.mediaId ?: "").put("position", c.currentPosition.coerceAtLeast(0))
                    .put("speed", c.playbackParameters.speed.toDouble()).put("volume", c.volume.toDouble())
                    .put("automatic", automatic)
            }
            backup.writeText(original.toString())
            // This is an audio continuity trial; do not start unrelated full-episode inference.
            graph.settings.flag("transcribeOnPlayback", false)
            File(directory, "playback-health.jsonl").delete()
            File(directory, "playback-health.enabled").writeText(id)
            withContext(Dispatchers.Main) {
                c.pause(); c.setMediaItem(mediaItem(dao.episode(id)!!, dao.podcast(id)), 0)
                c.setPlaybackSpeed(1f); c.volume = 0f; c.prepare()
            }
            withTimeout(30000) { while (!withContext(Dispatchers.Main) { c.playbackState == Player.STATE_READY }) delay(100) }
            delay(1500)
            File(directory, "playback-stage.json").writeText(JSONObject().put("durationMs", duration).put("bytes", file.length()).toString())
        } finally { withContext(Dispatchers.Main) { c.release() } }
    }

    @Test fun restoreLockedTrial() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lockedTrial") == "restore")
        graph.ready.await()
        check(backup.isFile) { "No private queue backup available" }
        val original = JSONObject(backup.readText())
        val dao = graph.db.library()
        val ids = original.getJSONArray("queue").let { list -> (0 until list.length()).map { list.getString(it) } }
        val episodes = ids.mapNotNull { dao.episode(it) }
        val podcasts = dao.podcasts().associateBy { it.id }
        val c = controller()
        try {
            delay(1500)
            withContext(Dispatchers.Main) {
                c.pause()
                if (episodes.isEmpty()) c.clearMediaItems() else {
                    val index = episodes.indexOfFirst { it.id == original.getString("current") }.coerceAtLeast(0)
                    c.setMediaItems(episodes.map { mediaItem(it, podcasts[it.podcastId]) }, index, original.getLong("position"))
                    c.prepare()
                }
                c.setPlaybackSpeed(original.getDouble("speed").toFloat()); c.volume = original.getDouble("volume").toFloat()
            }
            File(directory, "playback-health.enabled").delete()
            withTimeout(10000) { while (graph.activeFiles.busy(dao.episode(id)?.localFile ?: "")) delay(100) }
            graph.media.deleteDownload(id); dao.deletePodcast(id)
            delay(1500)
            assertEquals(episodes.map { it.id }, dao.queue().map { it.episodeId })
            assertEquals(original.getDouble("speed").toFloat(), dao.playerSession()!!.speed, 0.001f)
            if (original.has("automatic")) graph.settings.flag("transcribeOnPlayback", original.getBoolean("automatic"))
            backup.delete()
        } finally { withContext(Dispatchers.Main) { c.release() } }
    }

    @Test fun focusAndPersistFixture() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lockedTrial") == "controls")
        check(backup.isFile)
        val c = controller()
        val audio = context.getSystemService(AudioManager::class.java)
        var competing: AudioFocusRequest? = null
        try {
            withContext(Dispatchers.Main) { c.seekTo(10000); c.setPlaybackSpeed(1f); c.prepare(); c.play() }
            await { c.isPlaying }
            fun request(gain: Int) = AudioFocusRequest.Builder(gain)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener({}, Handler(Looper.getMainLooper())).build()
            competing = request(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(competing!!))
            await { !c.isPlaying }
            assertTrue(withContext(Dispatchers.Main) { c.playWhenReady })
            audio.abandonAudioFocusRequest(competing!!); competing = null
            await { c.isPlaying }
            competing = request(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(competing!!))
            await { !c.playWhenReady }
            audio.abandonAudioFocusRequest(competing!!); competing = null
            delay(1500)
            assertFalse(withContext(Dispatchers.Main) { c.isPlaying })
            withContext(Dispatchers.Main) { c.pause(); c.seekTo(12345); c.setPlaybackSpeed(1.25f) }
            delay(2000)
            assertEquals(12345L, graph.db.library().episode(id)!!.positionMs)
            assertEquals(1.25f, graph.db.library().playerSession()!!.speed, 0.001f)
        } finally {
            competing?.let { audio.abandonAudioFocusRequest(it) }
            withContext(Dispatchers.Main) { c.pause(); c.release() }
        }
    }

    @Test fun verifyFixtureAfterProcessDeath() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lockedTrial") == "reopen")
        check(backup.isFile)
        val c = controller()
        try {
            await { c.currentMediaItem?.mediaId == id && c.playbackState == Player.STATE_READY }
            withContext(Dispatchers.Main) {
                assertFalse(c.isPlaying); assertFalse(c.playWhenReady)
                assertEquals(12345L, c.currentPosition)
                assertEquals(1.25f, c.playbackParameters.speed, 0.001f)
                assertEquals(listOf(id), (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId })
            }
        } finally { withContext(Dispatchers.Main) { c.release() } }
    }

    private suspend fun await(predicate: () -> Boolean) = withTimeout(15000) {
        while (!withContext(Dispatchers.Main) { predicate() }) delay(100)
    }
}
