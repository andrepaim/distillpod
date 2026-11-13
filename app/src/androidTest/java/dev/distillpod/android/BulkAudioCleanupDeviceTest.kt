package dev.distillpod.android

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.*
import dev.distillpod.android.features.*
import dev.distillpod.android.playback.*
import dev.distillpod.android.video.*
import dev.distillpod.core.PcmWaveWriter
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Private Room/files and the real UI/registry; physical runs require the offline validation app. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class BulkAudioCleanupDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun reviewCancelAndConfirmKeepPlayingAndChangedCopiesAndSavedData() = runBlocking<Unit> {
        val context = guardedContext()
        // Discoverability/wiring in the actual settings page, without deleting its library.
        val graph = context.appGraph
        graph.ready.await()
        val vm = withContext(Dispatchers.Main) {
            ViewModelProvider(compose.activity, AppViewModel.factory(graph))[AppViewModel::class.java].also { it.open("settings") }
        }
        compose.onNodeWithText("Downloads & storage").performScrollTo().performClick()
        compose.onNodeWithTag("audio-cleanup-review").performScrollTo().performClick()
        compose.waitUntil(15000) { vm.audioCleanup.state.value.phase == AudioCleanupPhase.READY }
        compose.onNodeWithText(if (vm.audioCleanup.state.value.preview!!.canApply) "Cancel" else "Close").performClick()
        assertEquals(AudioCleanupPhase.IDLE, vm.audioCleanup.state.value.phase)

        fixture(context) { f ->
            val dao = f.db.library()
            val a = f.episode("a", "Playing fixture")
            val queued = f.episode("queued", "Queued fixture")
            val b = f.episode("b", "Replaceable fixture").copy(positionMs = 2345)
            val c = f.episode("c", "Unfinished fixture").copy(localFile = null, downloadState = "paused",
                positionMs = 3456, downloadedBytes = 6, etag = "fixture-validator")
            listOf(a, queued, b, c).forEach { dao.putEpisode(it) }
            check(File(f.media.directory, "c.audio").delete())
            val fileA = File(a.localFile!!)
            val fileB = File(b.localFile!!)
            val queuedFile = File(queued.localFile!!)
            val partial = File(f.media.directory, "${c.id}.partial").apply { writeText("resume") }
            val orphan = File(f.media.directory, "orphan.audio").apply { writeText("cache") }
            val model = File(f.media.modelsDirectory, "fixture.bin").apply { writeText("model") }
            listOf(a, queued, b, c).forEach { dao.putTranscript(TranscriptEntity(it.id, "revision", "fingerprint", "tiny", "en", state = "ready")) }
            dao.putAnalysis(AnalysisEntity(b.id, "revision", state = "succeeded", summary = "Saved summary"))
            dao.putDistillation(DistillationEntity("moment", b.id, 0, 1000, insight = "Saved insight", state = "saved"))
            val transcripts = dao.transcripts()
            val insight = dao.distillation("moment")
            val analysis = dao.analysis(b.id)
            val prepared = f.media.preparePlaybackItems(listOf(mediaItem(a, null))).single()
            withContext(Dispatchers.Main) {
                val engine = ExoPlayer.Builder(context).setMediaSourceFactory(VideocastMediaSourceFactory(context,
                    YouTubeSource { _, _, _ -> error("Offline fixture") }, f.media.active.audioOwnership)).build()
                f.player = OwnedAudioPlayer(engine, f.media.active.audioOwnership, f.media.directory.absolutePath).also {
                    it.volume = 0f; it.setMediaItem(prepared); it.prepare(); it.play()
                }
            }
            withTimeout(10000) { while (!withContext(Dispatchers.Main) { f.player!!.isPlaying }) delay(20) }
            // Keep playing and persisted-queue protection independent: neither masks the other.
            dao.insertQueue(listOf(QueueEntryEntity(queued.id, 0)))
            val refreshes = AtomicInteger()
            val errors = mutableListOf<Throwable>()
            val controller = AudioCleanupController(f.scope, f.media::previewAudioCleanup,
                { ticket, progress -> f.media.clearAudio(ticket, progress) }, errors::add)
            withContext(Dispatchers.Main) {
                compose.activity.setContent {
                    DistillTheme { Surface { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        BulkAudioSettings(controller) { refreshes.incrementAndGet() }
                    } } }
                }
            }
            fun review() {
                compose.onNodeWithTag("audio-cleanup-review").performScrollTo().performClick()
                compose.waitUntil(10000) { controller.state.value.phase == AudioCleanupPhase.READY }
            }
            review()
            assertEquals(2, controller.state.value.preview!!.protectedUnits)
            compose.onNodeWithText("Cancel").performClick()
            assertTrue(listOf(fileA, queuedFile, fileB, partial, orphan, model).all { it.isFile })
            assertEquals(b, dao.episode(b.id))
            review()
            val newer = File(f.media.directory, "new-copy.audio").apply { writeText("new copy") }
            val replacement = b.copy(localFile = newer.absolutePath, downloadedBytes = newer.length(), mediaFingerprint = "new-fingerprint")
            dao.putEpisode(replacement)
            compose.onNodeWithTag("audio-cleanup-confirm").performClick()
            compose.waitUntil(15000) { controller.state.value.phase in setOf(AudioCleanupPhase.FINISHED, AudioCleanupPhase.FAILED) }
            val result = controller.state.value.result!!
            assertEquals(AudioCleanupPhase.FINISHED, controller.state.value.phase)
            assertEquals(2, result.removed.files)
            assertEquals(11L, result.removed.bytes)
            assertEquals(2, result.protectedUnits)
            assertEquals(1, result.changedUnits)
            assertTrue(fileA.isFile); assertTrue(queuedFile.isFile); assertTrue(fileB.isFile); assertTrue(newer.isFile); assertTrue(model.isFile)
            assertFalse(partial.exists()); assertFalse(orphan.exists())
            assertEquals(replacement, dao.episode(b.id))
            assertEquals(3456L, dao.episode(c.id)!!.positionMs)
            assertEquals("none", dao.episode(c.id)!!.downloadState)
            assertEquals(transcripts, dao.transcripts())
            assertEquals(insight, dao.distillation("moment")); assertEquals(analysis, dao.analysis(b.id))
            assertEquals(listOf(queued.id), dao.queue().map { it.episodeId })
            withContext(Dispatchers.Main) {
                assertEquals(a.id, f.player!!.currentMediaItem!!.mediaId)
                assertTrue("The independent playing copy must remain usable", f.player!!.isPlaying)
            }
            assertTrue(errors.isEmpty())
            compose.waitUntil(10000) { refreshes.get() > 0 }
            compose.onNodeWithTag("audio-cleanup-result").performScrollTo().assertIsDisplayed()
            assertEquals(fileA.length() + queuedFile.length() + fileB.length() + newer.length(), f.media.storageUsage().quotaBytes)
        }
    }

    @Test fun partialWriterCancellationInvalidatesOldReviewsUntilItsStreamCloses() = runBlocking<Unit> {
        fixture(guardedContext()) { f ->
            val row = f.episode("partial", "Writer fixture").copy(localFile = null, downloadState = "paused",
                downloadedBytes = 6, etag = "same-validator")
            f.db.library().putEpisode(row)
            val partial = File(f.media.directory, "${row.id}.partial").apply { writeText("oldtry") }
            // episode() creates a completed fixture; discard it before reviewing this partial case.
            File(f.media.directory, "partial.audio").delete()
            val during = f.media.previewAudioCleanup()
            val after = f.media.previewAudioCleanup()
            val started = CompletableDeferred<Unit>()
            val writer = f.scope.launch(Dispatchers.IO) {
                f.media.withAudioPartialReservation(partial) {
                    partial.outputStream().use { output ->
                        output.write("newtry".toByteArray()); output.flush()
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
            }.also { f.writers += it }
            withTimeout(10000) { started.await() }
            val held = f.media.clearAudio(during)
            assertEquals(0, held.removed.files)
            assertTrue(held.protectedUnits + held.changedUnits > 0)
            assertEquals("newtry", partial.readText())
            writer.cancelAndJoin()
            val stale = f.media.clearAudio(after)
            assertEquals(0, stale.removed.files)
            assertEquals(1, stale.changedUnits)
            assertEquals("newtry", partial.readText())
            val fresh = f.media.clearAudio(f.media.previewAudioCleanup())
            assertEquals(1, fresh.removed.unfinished.files)
            assertEquals(6L, fresh.removed.bytes)
            assertFalse(partial.exists())
            assertEquals("none", f.db.library().episode(row.id)!!.downloadState)
        }
    }

    @Test fun canonicalProcessingLeaseProtectsDetachedAliasUntilReaderUnwinds() = runBlocking<Unit> {
        fixture(guardedContext()) { f ->
            val actual = f.episode("reader", "Reader fixture")
            val alias = File(f.media.directory, "../media/reader.audio")
            val row = actual.copy(localFile = alias.absolutePath, positionMs = 1234)
            val dao = f.db.library()
            dao.putEpisode(row)
            dao.putTranscript(TranscriptEntity(row.id, "revision", "fingerprint", "tiny", "en", state = "paused"))
            val transcript = dao.transcript(row.id)
            f.media.withDownloadedAudio(row.id) { selected ->
                assertEquals(alias.absolutePath, selected.absolutePath)
                selected.inputStream().use { input ->
                    assertTrue(input.read() >= 0)
                    dao.removeDownload(row.id)
                    val kept = f.media.clearAudio(f.media.previewAudioCleanup())
                    assertEquals(0, kept.removed.files)
                    assertTrue(kept.protectedUnits > 0)
                    assertTrue(File(actual.localFile!!).isFile)
                    assertTrue(input.read() >= 0)
                }
            }
            val removed = f.media.clearAudio(f.media.previewAudioCleanup())
            assertEquals(1, removed.removed.cached.files)
            assertFalse(File(actual.localFile!!).exists())
            assertEquals(1234L, dao.episode(row.id)!!.positionMs)
            assertEquals(transcript, dao.transcript(row.id))
        }
    }

    private fun guardedContext(): Context {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val offline = context.packageName == "dev.distillpod.android.validation.bulk60"
        check(offline || Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic")) {
            "Physical validation requires the isolated offline bulk-audio application"
        }
        if (offline) for (permission in listOf(Manifest.permission.INTERNET, Manifest.permission.RECORD_AUDIO))
            check(context.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED)
        return context
    }

    private class Fixture(val context: Context, val root: File, val db: AppDatabase,
        val media: MediaRepository, val scope: CoroutineScope) {
        val writers = mutableListOf<Job>()
        var player: OwnedAudioPlayer? = null
        suspend fun episode(id: String, title: String): EpisodeEntity {
            val file = File(media.directory, "$id.audio")
            PcmWaveWriter(file, 16000, 1).use { writer ->
                repeat(90) { writer.append(ByteBuffer.wrap(ByteArray(32000))) }; writer.finish()
            }
            return EpisodeEntity(id, "show", id, title, "https://example.test/$id",
                localFile = file.absolutePath, downloadState = "downloaded", downloadedBytes = file.length())
        }
    }

    private suspend fun fixture(context: Context, block: suspend (Fixture) -> Unit) {
        val root = File(context.cacheDir, "bulk-audio-" + UUID.randomUUID()).apply { check(mkdir()) }
        val owner = SupervisorJob()
        var database: AppDatabase? = null
        var fixture: Fixture? = null
        var primary: Throwable? = null
        try {
            val wrapped = object : ContextWrapper(context) { override fun getFilesDir(): File = root }
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build().also { database = it }
            val media = MediaRepository(wrapped, db, AppSettings(context), ResumableDownloader(OkHttpClient()), ActiveFiles())
            val f = Fixture(context, root, db, media, CoroutineScope(owner + Dispatchers.Main.immediate)).also { fixture = it }
            db.library().putPodcast(PodcastEntity("show", "https://example.test/feed", "Synthetic show"))
            withTimeout(45000) { block(f) }
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            var cleanupFailure = primary
            suspend fun cleanup(action: suspend () -> Unit) {
                try { action() } catch (error: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = error else if (cleanupFailure !== error) cleanupFailure!!.addSuppressed(error)
                }
            }
            cleanup { fixture?.writers?.forEach { it.cancelAndJoin() } }
            cleanup { owner.cancelAndJoin() }
            cleanup { withContext(Dispatchers.Main) { fixture?.player?.release() } }
            cleanup {
                withTimeout(10000) {
                    while (fixture?.media?.directory?.listFiles()?.any { fixture!!.media.active.busy(it.canonicalPath) } == true) delay(20)
                }
            }
            cleanup { database?.close() }
            cleanup {
                check(fixture?.media?.directory?.listFiles()?.none { fixture!!.media.active.busy(it.canonicalPath) } != false)
                check(root.deleteRecursively())
            }
            if (primary == null) cleanupFailure?.let { throw it }
        }
    }
}
