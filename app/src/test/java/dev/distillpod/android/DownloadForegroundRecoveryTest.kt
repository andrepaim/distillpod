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
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Android can refuse the download foreground service when a job starts in the background (#138).
 * The transfer then continues as ordinary work and never waits for the user (#292).
 */
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
    /** An unreferenced completer fails its future when collected (#186); WorkManager's queued start keeps its own. */
    private val pendingStarts = java.util.concurrent.ConcurrentLinkedQueue<CallbackToFutureAdapter.Completer<Void>>()
    /** Never completes: the worker is stopped while still waiting for its foreground service. */
    private fun pending(reached: CompletableDeferred<Unit>): ForegroundUpdater = ForegroundUpdater { _, _, _ ->
        CallbackToFutureAdapter.getFuture<Void> { pendingStarts += it; "pending" }.also { reached.complete(Unit) }
    }
    private fun worker(kind: String, id: String, attempt: Int, foreground: ForegroundUpdater, workId: java.util.UUID? = null) =
        TestListenableWorkerBuilder.from(context, LibraryWorker::class.java)
            .apply { workId?.let { setId(it) } }
            .setInputData(workDataOf("kind" to kind, "id" to id))
            .setRunAttemptCount(attempt)
            .setForegroundUpdater(foreground).build()
    private fun unfinished(name: String) = manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).filter { !it.state.isFinished }
    /** A collection may run at any time while the stopped worker still awaits its foreground start (#186). */
    private suspend fun awaitPendingStart(reached: CompletableDeferred<Unit>) {
        withTimeout(5000) { reached.await() }
        System.gc()
        System.runFinalization()
    }
    private suspend fun settle(check: suspend () -> Boolean) = withTimeout(10000) {
        while (!check()) { shadowOf(Looper.getMainLooper()).idle(); delay(10) }
    }

    /** Serves every transfer, including a model's fixed URL, from the loopback server. */
    private fun transfersFrom(graph: AppGraph, server: MockWebServer) {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build()
        org.robolectric.util.ReflectionHelpers.setField(graph, "media",
            MediaRepository(context, graph.db, graph.settings, ResumableDownloader(client), graph.activeFiles))
    }
    private fun throttled(body: String) = MockResponse().setBody(body).setHeader("ETag", "\"v1\"")
        .throttleBody(1024, 50, TimeUnit.MILLISECONDS)

    @Test fun `a rejected episode foreground start continues as ordinary work and completes`() = runBlocking {
        fixture { graph ->
            val dao = graph.db.library()
            MockWebServer().use { server ->
                val audio = "a".repeat(5000)
                dao.putEpisode(episode.copy(audioUrl = server.url("/audio.mp3").toString()))
                // The attempt count that used to pause the row (#138) no longer matters (#292).
                for (attempt in listOf(0, 4)) {
                    server.enqueue(MockResponse().setBody(audio).setHeader("ETag", "\"v1\""))
                    if (attempt == 4) graph.media.deleteDownload(episode.id)
                    graph.jobs.download(episode.id)
                    assertEquals(ListenableWorker.Result.success(), worker("download", episode.id, attempt, rejected()).doWork())
                    val row = dao.episode(episode.id)!!
                    assertEquals("downloaded", row.downloadState)
                    assertNull(row.error)
                    assertEquals(audio, java.io.File(row.localFile!!).readText())
                }
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun `after a rejected start a stop at the time limit is rescheduled and resumes from the saved bytes`() = runBlocking {
        fixture { graph ->
            val dao = graph.db.library()
            MockWebServer().use { server ->
                val audio = "b".repeat(200000)
                server.enqueue(throttled(audio))
                dao.putEpisode(episode.copy(audioUrl = server.url("/audio.mp3").toString()))
                graph.jobs.download(episode.id)
                val worker = worker("download", episode.id, 4, rejected())
                val run = launch(Dispatchers.IO) { worker.doWork() }
                settle { dao.episode(episode.id)?.let { it.downloadState == "downloading" && it.downloadedBytes > 0 } == true }
                worker.stop(WorkInfo.STOP_REASON_TIMEOUT)
                run.cancelAndJoin()
                val stopped = dao.episode(episode.id)!!
                assertEquals("queued", stopped.downloadState)
                assertNull(stopped.error)
                assertTrue(stopped.downloadedBytes in 1 until audio.length)
                assertEquals(1, unfinished("download:${episode.id}").size)

                // WorkManager reruns the request; Android refuses the foreground start again.
                val saved = stopped.downloadedBytes
                server.enqueue(MockResponse().setResponseCode(206).setBody(audio.substring(saved.toInt())).setHeader("ETag", "\"v1\"")
                    .setHeader("Content-Range", "bytes $saved-${audio.length - 1}/${audio.length}"))
                assertEquals(ListenableWorker.Result.success(), worker("download", episode.id, 5, rejected()).doWork())
                server.takeRequest()
                val resumed = server.takeRequest()
                assertEquals("bytes=$saved-", resumed.getHeader("Range"))
                assertEquals("\"v1\"", resumed.getHeader("If-Range"))
                val row = dao.episode(episode.id)!!
                assertEquals("downloaded", row.downloadState)
                assertEquals(audio, java.io.File(row.localFile!!).readText())
            }
        }
    }

    @Test fun `a pause during a transfer whose foreground start was rejected stays paused`() = runBlocking {
        fixture { graph ->
            val dao = graph.db.library()
            MockWebServer().use { server ->
                server.enqueue(throttled("c".repeat(200000)))
                dao.putEpisode(episode.copy(audioUrl = server.url("/audio.mp3").toString()))
                graph.jobs.download(episode.id)
                val worker = worker("download", episode.id, 0, rejected())
                val run = launch(Dispatchers.IO) { worker.doWork() }
                settle { dao.episode(episode.id)?.let { it.downloadState == "downloading" && it.downloadedBytes > 0 } == true }
                // Pause cancels the request; WorkManager then stops the worker as an app cancellation.
                graph.jobs.pauseDownload(episode.id)
                worker.stop(WorkInfo.STOP_REASON_CANCELLED_BY_APP)
                run.cancelAndJoin()
                assertEquals("paused", dao.episode(episode.id)!!.downloadState)
                assertNull(dao.episode(episode.id)!!.error)
                graph.jobs.recoverDownloads()
                assertEquals("paused", dao.episode(episode.id)!!.downloadState)
                assertTrue(unfinished("download:${episode.id}").isEmpty())
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun `concurrent rejected attempts for one episode make a single transfer`() = runBlocking {
        fixture { graph ->
            val dao = graph.db.library()
            MockWebServer().use { server ->
                val audio = "d".repeat(20000)
                server.enqueue(throttled(audio))
                dao.putEpisode(episode.copy(audioUrl = server.url("/audio.mp3").toString()))
                graph.jobs.download(episode.id)
                val results = (0..1).map { async(Dispatchers.IO) { worker("download", episode.id, it, rejected()).doWork() } }.awaitAll()
                assertEquals(listOf(ListenableWorker.Result.success(), ListenableWorker.Result.success()), results)
                assertEquals(1, server.requestCount)
                assertEquals(audio, java.io.File(dao.episode(episode.id)!!.localFile!!).readText())
            }
        }
    }

    @Test fun `a rejected model foreground start continues, and a stop at the time limit resumes from the saved bytes`() = runBlocking {
        fixture { graph ->
            val dao = graph.db.library()
            MockWebServer().use { server ->
                transfersFrom(graph, server)
                graph.jobs.model("tiny")
                // A chunked body has no length to compare with the model's size.
                server.enqueue(MockResponse().setChunkedBody("m".repeat(200000), 4096).setHeader("ETag", "\"m1\"")
                    .throttleBody(1024, 50, TimeUnit.MILLISECONDS))
                val worker = worker("model", "tiny", 4, rejected())
                val run = launch(Dispatchers.IO) { worker.doWork() }
                settle { dao.model("tiny")?.let { it.state == "downloading" && it.bytes > 0 } == true }
                worker.stop(WorkInfo.STOP_REASON_TIMEOUT)
                run.cancelAndJoin()
                val stopped = dao.model("tiny")!!
                assertEquals("queued", stopped.state)
                assertNull(stopped.error)
                assertTrue(stopped.bytes > 0)
                assertEquals(1, unfinished("model:tiny").size)

                val total = whisperModels.single { it.id == "tiny" }.bytes
                server.enqueue(MockResponse().setResponseCode(206).setChunkedBody("m".repeat(200000), 4096).setHeader("ETag", "\"m1\"")
                    .setHeader("Content-Range", "bytes ${stopped.bytes}-${total - 1}/$total").throttleBody(1024, 50, TimeUnit.MILLISECONDS))
                val next = worker("model", "tiny", 5, rejected())
                val resumed = launch(Dispatchers.IO) { next.doWork() }
                settle { dao.model("tiny")?.let { it.state == "downloading" && it.bytes > stopped.bytes } == true }
                next.stop(WorkInfo.STOP_REASON_TIMEOUT)
                resumed.cancelAndJoin()
                server.takeRequest()
                assertEquals("bytes=${stopped.bytes}-", server.takeRequest().getHeader("Range"))
                assertEquals("queued", dao.model("tiny")!!.state)
            }
        }
    }

    @Test fun `startup resumes rows an earlier version paused because Android refused the foreground start`() = runBlocking {
        fixture { graph ->
            val dao = graph.db.library()
            dao.putEpisode(episode.copy(downloadState = "paused", downloadedBytes = 10, error = DOWNLOAD_BLOCKED_MESSAGE))
            val userPaused = episode.copy(id = "user-paused", guid = "user-paused", downloadState = "paused", downloadedBytes = 10)
            val failed = episode.copy(id = "failed", guid = "failed", downloadState = "failed", error = "The transfer was interrupted.")
            dao.putEpisode(userPaused); dao.putEpisode(failed)
            dao.putModel(ModelEntity("tiny", "paused", 10, "\"m1\"", MODEL_BLOCKED_MESSAGE))
            dao.putModel(ModelEntity("base", "paused", 10))
            graph.jobs.recoverDownloads()
            assertEquals("queued", dao.episode(episode.id)!!.downloadState)
            assertNull(dao.episode(episode.id)!!.error)
            assertEquals(10, dao.episode(episode.id)!!.downloadedBytes)
            assertEquals(1, unfinished("download:${episode.id}").size)
            assertEquals("queued", dao.model("tiny")!!.state)
            assertNull(dao.model("tiny")!!.error)
            assertEquals("\"m1\"", dao.model("tiny")!!.etag)
            assertEquals(1, unfinished("model:tiny").size)
            // A user's pause and a failure still wait for the user.
            assertEquals("paused", dao.episode(userPaused.id)!!.downloadState)
            assertEquals("failed", dao.episode(failed.id)!!.downloadState)
            assertEquals("paused", dao.model("base")!!.state)
            assertTrue(unfinished("download:${userPaused.id}").isEmpty())
            assertTrue(unfinished("download:${failed.id}").isEmpty())
            assertTrue(unfinished("model:base").isEmpty())
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

    @Test fun `a rejected start never rewrites a completed download`() = runBlocking {
        fixture { graph ->
            val dao = graph.db.library()
            val file = java.io.File(graph.media.directory, "${episode.id}-done.audio").apply { writeText("done") }
            dao.putEpisode(episode.copy(downloadState = "downloaded", localFile = file.path, downloadedBytes = 4, totalBytes = 4))
            assertEquals(ListenableWorker.Result.success(), worker("download", episode.id, 4, rejected()).doWork())
            assertEquals("downloaded", dao.episode(episode.id)!!.downloadState)
            assertEquals(file.path, dao.episode(episode.id)!!.localFile)
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
                    // Wait for written bytes reported by this transfer. The row starts each phase queued;
                    // bytes kept from the previous phase alone let the stop land before doWork reached the
                    // transfer, and the transfer's first progress report precedes any written byte.
                    settle { dao.episode(episode.id)?.let { it.downloadState == "downloading" && it.downloadedBytes > 0 } == true }
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
            awaitPendingStart(reached)
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
                awaitPendingStart(reached)
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
                awaitPendingStart(reached)
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
