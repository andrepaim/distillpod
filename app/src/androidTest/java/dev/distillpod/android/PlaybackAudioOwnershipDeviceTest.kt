package dev.distillpod.android

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.*
import dev.distillpod.android.playback.*
import dev.distillpod.core.PcmWaveWriter
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private fun ownershipTestContext(): Context {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val offline = context.packageName == "dev.distillpod.android.validation.playback60"
    check(offline || Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic")) {
        "Physical execution requires the isolated offline playback validation app"
    }
    if (offline) for (permission in listOf(Manifest.permission.INTERNET, Manifest.permission.RECORD_AUDIO)) {
        check(context.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED)
    }
    return context
}

private fun syntheticWave(file: File) {
    PcmWaveWriter(file, 16000, 1).use { writer ->
        repeat(30) { writer.append(ByteBuffer.wrap(ByteArray(32000))) }
        writer.finish()
    }
}

private suspend fun cleanupOwnershipFixture(primary: Throwable?, vararg steps: suspend () -> Unit) {
    var failure = primary
    for (step in steps) try { step() } catch (cleanup: Throwable) {
        if (failure == null) failure = cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup)
    }
    if (primary == null) failure?.let { throw it }
}

/** Actual MediaSession admission, persisted queue and local playback; no production phone data. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackAudioOwnershipDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun playingAndQueuedDownloadsStayProtectedThroughMoveRemoveAndClear() = runBlocking<Unit> {
        val context = ownershipTestContext()
        val graph = context.appGraph
        graph.ready.await()
        val dao = graph.db.library()
        val oldQueue = dao.queue()
        val oldSession = dao.playerSession()
        if (context.packageName.endsWith(".playback60")) {
            check(oldQueue.isEmpty() && dao.episodes().isEmpty()) { "Use a fresh offline validation app" }
        }
        val tag = "playback-owner-" + UUID.randomUUID()
        val ids = listOf("$tag-a", "$tag-b")
        val files = ids.map { File(graph.media.directory, "$it.wav") }
        val transcripts = ids.map { TranscriptEntity(it, "fixture-revision", "", "tiny", "en", state = "ready") }
        val controller = graph.playback.sessionPlayer()
        val oldVolume = withContext(Dispatchers.Main) { controller.volume }
        val oldTranscribe = graph.settings.flow.first().transcribeOnPlayback
        suspend fun awaitQueue(expected: List<String>) = withTimeout(15000) {
            graph.playback.state.first { it.queue == expected }
            graph.playback.awaitQueueWritesProcessed()
            while (dao.queue().map { it.episodeId } != expected) delay(50)
        }
        suspend fun deleteAfterRelease(id: String) = withTimeout(10000) {
            while (true) {
                try { graph.media.deleteDownload(id); break }
                catch (_: AudioDeletionBlocked) { delay(50) }
            }
        }
        var primary: Throwable? = null
        var queueCleared = false
        try {
            files.forEach(::syntheticWave)
            withContext(Dispatchers.Main) { controller.volume = 0f }
            graph.settings.flag("transcribeOnPlayback", false)
            withTimeout(10000) { graph.preferences.first { !it.transcribeOnPlayback } }
            graph.playback.clear()
            awaitQueue(emptyList())
            dao.putPodcast(PodcastEntity(tag, "https://example.test/$tag", "Synthetic playback"))
            ids.forEachIndexed { index, id ->
                dao.putEpisode(EpisodeEntity(id, tag, id, "Synthetic item $index", "https://example.test/$id",
                    localFile = files[index].absolutePath, downloadState = "downloaded", durationMs = 30000))
                dao.putTranscript(transcripts[index])
            }
            val persistedTranscripts = ids.map { dao.transcript(it)!! }
            graph.playback.play(ids[0], 2000)
            withTimeout(10000) { graph.playback.state.first { it.playing } }
            graph.playback.enqueue(ids[1])
            awaitQueue(ids)
            for (id in ids) {
                assertTrue(runCatching { graph.media.deleteDownload(id) }.exceptionOrNull() is AudioDeletionBlocked)
            }
            assertTrue(files.all { it.isFile })
            withContext(Dispatchers.Main) { assertNull(controller.playerError) }
            graph.playback.move(ids[1], -1)
            awaitQueue(ids.reversed())
            assertEquals(ids[0], graph.playback.state.value.episodeId)
            graph.playback.remove(ids[1])
            awaitQueue(listOf(ids[0]))
            deleteAfterRelease(ids[1])
            assertFalse(files[1].exists())
            assertTrue(files[0].isFile)
            graph.playback.pause()
            graph.playback.seek(4321)
            graph.playback.awaitQueueWritesProcessed()
            graph.playback.clear()
            awaitQueue(emptyList())
            deleteAfterRelease(ids[0])
            assertFalse(files[0].exists())
            assertEquals(4321L, dao.episode(ids[0])!!.positionMs)
            assertNull(dao.playerSession()?.episodeId)
            for (index in ids.indices) assertEquals(persistedTranscripts[index], dao.transcript(ids[index]))
            withContext(Dispatchers.Main) { assertNull(controller.playerError) }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            cleanupOwnershipFixture(primary, {
                graph.playback.pause()
                graph.playback.clear()
                awaitQueue(emptyList())
                queueCleared = true
            }, { withContext(Dispatchers.Main) { controller.volume = oldVolume } }, {
                graph.settings.flag("transcribeOnPlayback", oldTranscribe)
            }, { dao.deletePodcast(tag) }, {
                check(queueCleared) { "Preserving synthetic files until the player releases them" }
                withTimeout(10000) {
                    for (file in files) {
                        while (graph.activeFiles.busy(file.canonicalPath)) delay(50)
                        check(!file.exists() || file.delete())
                    }
                }
            }, {
                check(queueCleared) { "Cannot restore fixture queue before clear completes" }
                graph.db.withTransaction {
                    dao.clearQueue()
                    dao.insertQueue(oldQueue)
                    oldSession?.let { dao.putPlayerSession(it) }
                }
                graph.playback.synchronizeRestoredQueue()
            })
        }
    }
}

/** Controlled real ExoPlayer/FileDataSource cancellation overlap, on Android loader threads. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackAudioLoaderDeviceTest {
    @Test fun removedLoaderCannotOpenADeletedGeneration() = loaderFixture(beforeOpen = true)
    @Test fun removedLoaderKeepsItsOpenFileProtectedUntilClose() = loaderFixture(beforeOpen = false)

    private fun loaderFixture(beforeOpen: Boolean) = runBlocking<Unit> {
        val context = ownershipTestContext()
        val root = File(context.cacheDir, "loader-owner-" + UUID.randomUUID()).apply { check(mkdir()) }
        val synthetic = object : ContextWrapper(context) { override fun getFilesDir(): File = root }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val firstRead = AtomicBoolean(true)
        val openFailure = AtomicReference<Throwable?>()
        val actualOpen = AtomicBoolean(false)
        val observedError = AtomicReference<PlaybackException?>()
        var player: OwnedAudioPlayer? = null
        var ownership: AudioFileOwnership? = null
        val fixturePath = File(root, "media/fixture.wav").canonicalPath
        var primary: Throwable? = null
        // Ignore cancellation interruption only inside this bounded fixture gate. The removed
        // loader must finish its attempted IO after the test releases it; never leave a thread held.
        fun gate() {
            entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var interrupted = false
            try {
                while (true) {
                    val left = deadline - System.nanoTime()
                    check(left > 0) { "Synthetic loader gate timed out" }
                    try { check(resume.await(left, TimeUnit.NANOSECONDS)); break }
                    catch (_: InterruptedException) { interrupted = true }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt() }
        }
        try {
            val media = MediaRepository(synthetic, db, AppSettings(context), ResumableDownloader(OkHttpClient()), ActiveFiles())
            ownership = media.active.audioOwnership
            val file = File(media.directory, "fixture.wav").also(::syntheticWave)
            val episode = EpisodeEntity("episode", "show", "guid", "Synthetic loader", "https://example.test/audio",
                localFile = file.absolutePath, downloadState = "downloaded", positionMs = 4321)
            db.library().putPodcast(PodcastEntity("show", "https://example.test/feed", "Synthetic show"))
            db.library().putEpisode(episode)
            val item = media.preparePlaybackItems(listOf(mediaItem(episode, null))).single()
            val selection = item.localConfiguration!!.tag as AudioPlaybackSelection
            val currentSelection = AtomicReference(selection)
            val factory = DataSource.Factory {
                val local = FileDataSource()
                // The read gate is injected below the production factory's ownership wrapper.
                // The before-open case gates above it to exercise a revoked late acquisition.
                val source: DataSource = if (beforeOpen)
                    OwnedAudioDataSource(currentSelection.get(), media.active.audioOwnership, local) else local
                object : DataSource by source {
                    override fun open(spec: DataSpec): Long {
                        if (beforeOpen) gate()
                        try { return source.open(spec).also { actualOpen.set(true) } }
                        catch (failure: Throwable) { openFailure.set(failure); throw failure }
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (!beforeOpen && firstRead.getAndSet(false)) gate()
                        return source.read(buffer, offset, length)
                    }
                    override fun close() { try { source.close() } finally { closed.countDown() } }
                }
            }
            withContext(Dispatchers.Main) {
                val sources = if (beforeOpen) ProgressiveMediaSource.Factory(factory) else
                    dev.distillpod.android.video.VideocastMediaSourceFactory(context,
                        dev.distillpod.android.video.YouTubeSource { _, _, _ -> error("Offline fixture") },
                        media.active.audioOwnership, factory)
                val engine = ExoPlayer.Builder(context).setMediaSourceFactory(sources).build()
                engine.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { observedError.compareAndSet(null, error) }
                })
                player = OwnedAudioPlayer(engine, media.active.audioOwnership, media.directory.absolutePath).also {
                    it.volume = 0f
                    it.setMediaItem(item)
                    it.prepare()
                }
            }
            assertTrue("Loader did not reach the controlled boundary", entered.await(10, TimeUnit.SECONDS))
            withContext(Dispatchers.Main) { player!!.clearMediaItems() }
            if (beforeOpen) {
                media.deleteDownload(episode.id)
                assertFalse(file.exists())
            } else {
                assertTrue(actualOpen.get())
                val failure = runCatching { media.deleteDownload(episode.id) }.exceptionOrNull()
                assertEquals(AudioDeletionBlockReason.ACTIVE, (failure as? AudioDeletionBlocked)?.reason)
                assertTrue(file.isFile)
                assertEquals(episode, db.library().episode(episode.id))
            }
            resume.countDown()
            assertTrue("Removed loader did not close", closed.await(10, TimeUnit.SECONDS))
            if (beforeOpen) {
                assertFalse(actualOpen.get())
                assertTrue(openFailure.get() is IOException)
            } else {
                withTimeout(10000) { while (media.active.audioOwnership.busy(file.canonicalPath)) delay(20) }
                media.deleteDownload(episode.id)
                assertFalse(file.exists())
            }
            assertEquals(4321L, db.library().episode(episode.id)!!.positionMs)
            // A close callback alone does not drain playback-thread cancellation. Require a
            // fresh generation to reach READY and advance, retaining transient errors as well.
            syntheticWave(file)
            db.library().putEpisode(episode)
            val fresh = media.preparePlaybackItems(listOf(mediaItem(episode, null))).single()
            currentSelection.set(fresh.localConfiguration!!.tag as AudioPlaybackSelection)
            withContext(Dispatchers.Main) { player!!.setMediaItem(fresh); player!!.prepare(); player!!.play() }
            withTimeout(10000) {
                while (!withContext(Dispatchers.Main) {
                    player!!.playbackState == Player.STATE_READY && player!!.isPlaying && player!!.currentPosition >= 100
                }) delay(20)
            }
            assertNull("Removed loader must not surface an error", observedError.get())
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            resume.countDown()
            cleanupOwnershipFixture(primary, {
                withContext(Dispatchers.Main) { player?.release() }
            }, {
                if (entered.count == 0L) check(closed.await(10, TimeUnit.SECONDS)) { "Loader cleanup did not close" }
                withTimeout(10000) { while (ownership?.busy(fixturePath) == true) delay(20) }
            }, { db.close() }, {
                check(entered.count != 0L || closed.count == 0L) { "Preserving files for the unclosed fixture reader" }
                check(ownership?.busy(fixturePath) != true) { "Preserving files for the recovery reader" }
                check(root.deleteRecursively())
            })
        }
    }
}
