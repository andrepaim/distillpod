package dev.distillpod.android

import android.content.Context
import android.os.Looper
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.android.data.*
import dev.distillpod.android.work.DOWNLOAD_BLOCKED_MESSAGE
import dev.distillpod.android.work.LibraryWorker
import dev.distillpod.android.work.MODEL_BLOCKED_MESSAGE
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Android can refuse the download foreground service when a job starts in the background (#138). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadForegroundRecoveryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val manager get() = WorkManager.getInstance(context)
    private val episode = EpisodeEntity("fgs-episode", "fgs-show", "guid", "Episode", "https://example.test/audio.mp3",
        mediaFingerprint = "fixture-audio")

    private suspend fun fixture(block: suspend (AppGraph) -> Unit) {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            // Scheduled requests stay unfinished; each test drives the real worker explicitly.
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(context: Context, name: String, params: WorkerParameters): ListenableWorker? =
                    if (name == LibraryWorker::class.java.name) HeldWorker(context, params) else null
            }).build())
        val inactive = SupervisorJob().apply { cancel() }
        val graph = AppGraph(context, CoroutineScope(inactive + Dispatchers.Main.immediate))
        // AppJobs and LibraryWorker resolve context.appGraph internally.
        org.robolectric.util.ReflectionHelpers.setField(context, "graph\$delegate", lazyOf(graph))
        assertSame(graph, context.appGraph)
        graph.ready.complete(Unit)
        graph.settings.flag("refresh", false)
        graph.db.library().putPodcast(PodcastEntity(episode.podcastId, "https://example.test/feed", "Show"))
        graph.db.library().putEpisode(episode)
        try { block(graph) }
        finally {
            manager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
            graph.db.close()
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }

    private fun rejected(): ForegroundUpdater = ForegroundUpdater { _, _, _ ->
        CallbackToFutureAdapter.getFuture<Void> { it.setException(android.app.ForegroundServiceStartNotAllowedException("Synthetic background start")); "rejected" }
    }
    private fun failing(error: Exception): ForegroundUpdater = ForegroundUpdater { _, _, _ ->
        CallbackToFutureAdapter.getFuture<Void> { it.setException(error); "failing" }
    }
    private fun allowed(): ForegroundUpdater = ForegroundUpdater { _, _, _ ->
        CallbackToFutureAdapter.getFuture<Void> { it.set(null); "allowed" }
    }
    /** Never completes: the worker is stopped while still waiting for its foreground service. */
    private fun pending(reached: CompletableDeferred<Unit>): ForegroundUpdater = ForegroundUpdater { _, _, _ ->
        reached.complete(Unit)
        CallbackToFutureAdapter.getFuture<Void> { "pending" }
    }
    private fun worker(kind: String, id: String, attempt: Int, foreground: ForegroundUpdater, workId: java.util.UUID? = null) =
        TestListenableWorkerBuilder.from(context, LibraryWorker::class.java)
            .apply { workId?.let { setId(it) } }
            .setInputData(workDataOf("kind" to kind, "id" to id))
            .setRunAttemptCount(attempt)
            .setForegroundUpdater(foreground).build()
    private fun unfinished(name: String) = manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).filter { !it.state.isFinished }
    private suspend fun settle(check: suspend () -> Boolean) = withTimeout(10000) {
        while (!check()) { shadowOf(Looper.getMainLooper()).idle(); delay(10) }
    }

    @Test fun `rejected episode foreground start retries, then pauses with an actionable message`() = runBlocking {
        fixture { graph ->
            val dao = graph.db.library()
            graph.jobs.download(episode.id)
            assertEquals("queued", dao.episode(episode.id)!!.downloadState)
            for (attempt in 0..3) {
                assertEquals(ListenableWorker.Result.retry(), worker("download", episode.id, attempt, rejected()).doWork())
                // WorkManager still owns the retried request, so the row stays queued without an error.
                assertEquals("queued", dao.episode(episode.id)!!.downloadState)
                assertNull(dao.episode(episode.id)!!.error)
            }
            assertEquals(ListenableWorker.Result.failure(), worker("download", episode.id, 4, rejected()).doWork())
            assertEquals("paused", dao.episode(episode.id)!!.downloadState)
            assertEquals(DOWNLOAD_BLOCKED_MESSAGE, dao.episode(episode.id)!!.error)
            assertTrue(DOWNLOAD_BLOCKED_MESSAGE.contains("Open DistillPod"))
            // Resume clears the message and schedules a fresh attempt.
            graph.jobs.download(episode.id)
            assertEquals("queued", dao.episode(episode.id)!!.downloadState)
            assertNull(dao.episode(episode.id)!!.error)
            assertEquals(1, unfinished("download:${episode.id}").size)
        }
    }

    @Test fun `rejected model foreground start retries, then pauses with an actionable message`() = runBlocking {
        fixture { graph ->
            val dao = graph.db.library()
            graph.jobs.model("tiny")
            assertEquals("queued", dao.model("tiny")!!.state)
            assertEquals(ListenableWorker.Result.retry(), worker("model", "tiny", 0, rejected()).doWork())
            assertEquals("queued", dao.model("tiny")!!.state)
            assertNull(dao.model("tiny")!!.error)
            assertEquals(ListenableWorker.Result.failure(), worker("model", "tiny", 4, rejected()).doWork())
            assertEquals("paused", dao.model("tiny")!!.state)
            assertEquals(MODEL_BLOCKED_MESSAGE, dao.model("tiny")!!.error)
            graph.jobs.model("tiny")
            assertEquals("queued", dao.model("tiny")!!.state)
            assertNull(dao.model("tiny")!!.error)
        }
    }

    @Test fun `a permanent foreground error never leaves a queued row without work`() = runBlocking {
        fixture { graph ->
            val dao = graph.db.library()
            graph.jobs.download(episode.id)
            assertEquals(ListenableWorker.Result.failure(),
                worker("download", episode.id, 0, failing(IllegalStateException("Not a background restriction"))).doWork())
            assertEquals("failed", dao.episode(episode.id)!!.downloadState)
            assertNotNull(dao.episode(episode.id)!!.error)
            graph.jobs.model("tiny")
            assertEquals(ListenableWorker.Result.failure(),
                worker("model", "tiny", 0, failing(SecurityException("Missing permission"))).doWork())
            assertEquals("failed", dao.model("tiny")!!.state)
        }
    }

    @Test fun `a rejection cannot overwrite a pause or completed download`() = runBlocking {
        fixture { graph ->
            val dao = graph.db.library()
            graph.jobs.download(episode.id)
            graph.jobs.pauseDownload(episode.id)
            assertEquals(ListenableWorker.Result.failure(), worker("download", episode.id, 4, rejected()).doWork())
            assertEquals("paused", dao.episode(episode.id)!!.downloadState)
            assertNull(dao.episode(episode.id)!!.error)
        }
    }

    @Test fun `pausing before start persists and app startup does not resume it`() = runBlocking {
        fixture { graph ->
            val dao = graph.db.library()
            graph.jobs.download(episode.id)
            graph.jobs.model("tiny")
            assertEquals(1, unfinished("download:${episode.id}").size)
            graph.jobs.pauseDownload(episode.id)
            graph.jobs.pauseModel("tiny")
            assertEquals("paused", dao.episode(episode.id)!!.downloadState)
            assertEquals("paused", dao.model("tiny")!!.state)
            assertTrue(unfinished("download:${episode.id}").isEmpty())
            assertTrue(unfinished("model:tiny").isEmpty())
            graph.jobs.recoverDownloads()
            assertEquals("paused", dao.episode(episode.id)!!.downloadState)
            assertEquals("paused", dao.model("tiny")!!.state)
            assertTrue(unfinished("download:${episode.id}").isEmpty())
            assertTrue(unfinished("model:tiny").isEmpty())
            // Startup still restores interrupted transfers.
            dao.putEpisode(dao.episode(episode.id)!!.copy(downloadState = "downloading"))
            graph.jobs.recoverDownloads()
            assertEquals(1, unfinished("download:${episode.id}").size)
        }
    }

    @Test fun `pausing never rewrites a completed download`() = runBlocking {
        fixture { graph ->
            val dao = graph.db.library()
            dao.putEpisode(episode.copy(downloadState = "downloaded", localFile = "/missing", downloadedBytes = 5, totalBytes = 5))
            graph.jobs.pauseDownload(episode.id)
            assertEquals("downloaded", dao.episode(episode.id)!!.downloadState)
            dao.putModel(ModelEntity("tiny", "ready", 5))
            graph.jobs.pauseModel("tiny")
            assertEquals("ready", dao.model("tiny")!!.state)
        }
    }

    @Test fun `a system stop keeps an in-flight episode download queued, an app cancellation pauses it`() = runBlocking {
        fixture { graph ->
            val dao = graph.db.library()
            MockWebServer().use { server ->
                suspend fun stopDuringTransfer(reason: Int) {
                    server.enqueue(MockResponse().setBody("a".repeat(200000)).setHeader("ETag", "\"v1\"")
                        .throttleBody(1024, 50, TimeUnit.MILLISECONDS))
                    val worker = worker("download", episode.id, 0, allowed())
                    val run = launch(Dispatchers.IO) { worker.doWork() }
                    settle { (dao.episode(episode.id)?.downloadedBytes ?: 0) > 0 }
                    worker.stop(reason)
                    run.cancelAndJoin()
                }
                dao.putEpisode(episode.copy(audioUrl = server.url("/audio.mp3").toString()))
                graph.jobs.download(episode.id)
                stopDuringTransfer(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY)
                assertEquals("queued", dao.episode(episode.id)!!.downloadState)
                assertTrue(dao.episode(episode.id)!!.downloadedBytes > 0)
                assertNull(dao.episode(episode.id)!!.error)

                stopDuringTransfer(WorkInfo.STOP_REASON_CANCELLED_BY_APP)
                assertEquals("paused", dao.episode(episode.id)!!.downloadState)
                assertTrue(dao.episode(episode.id)!!.downloadedBytes > 0)
            }
        }
    }

    @Test fun `a system stop after the request was cancelled stays paused`() = runBlocking {
        fixture { graph ->
            val dao = graph.db.library()
            graph.jobs.download(episode.id)
            graph.jobs.pauseDownload(episode.id)
            val reached = CompletableDeferred<Unit>()
            val worker = worker("download", episode.id, 0, pending(reached))
            val run = launch(Dispatchers.IO) { worker.doWork() }
            withTimeout(5000) { reached.await() }
            worker.stop(WorkInfo.STOP_REASON_TIMEOUT)
            run.cancelAndJoin()
            assertEquals("paused", dao.episode(episode.id)!!.downloadState)
        }
    }

    @Test fun `a system stop keeps a model download queued, an app cancellation pauses it`() = runBlocking {
        fixture { graph ->
            val dao = graph.db.library()
            graph.jobs.model("tiny")
            for ((reason, expected) in listOf(WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT to "queued",
                    WorkInfo.STOP_REASON_TIMEOUT to "queued", WorkInfo.STOP_REASON_CANCELLED_BY_APP to "paused")) {
                val reached = CompletableDeferred<Unit>()
                val worker = worker("model", "tiny", 0, pending(reached))
                val run = launch(Dispatchers.IO) { worker.doWork() }
                withTimeout(5000) { reached.await() }
                worker.stop(reason)
                run.cancelAndJoin()
                assertEquals("stop reason $reason", expected, dao.model("tiny")!!.state)
                assertNull(dao.model("tiny")!!.error)
            }
        }
    }

    @Config(sdk = [30])
    @Test fun `without platform stop reasons a cancelled request pauses and a system stop stays queued`() = runBlocking {
        fixture { graph ->
            val dao = graph.db.library()
            graph.jobs.download(episode.id)
            for (cancel in listOf(false, true)) {
                val request = unfinished("download:${episode.id}").single().id
                val reached = CompletableDeferred<Unit>()
                val worker = worker("download", episode.id, 0, pending(reached), request)
                val run = launch(Dispatchers.IO) { worker.doWork() }
                withTimeout(5000) { reached.await() }
                if (cancel) manager.cancelWorkById(request).result.get(5, TimeUnit.SECONDS)
                worker.stop(WorkInfo.STOP_REASON_UNKNOWN)
                run.cancelAndJoin()
                assertEquals(if (cancel) "paused" else "queued", dao.episode(episode.id)!!.downloadState)
            }
        }
    }
}

private class HeldWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val completion = CompletableDeferred<Result>()
    override suspend fun doWork(): Result = completion.await()
}
