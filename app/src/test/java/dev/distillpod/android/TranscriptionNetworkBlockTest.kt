package dev.distillpod.android

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.android.data.*
import dev.distillpod.android.diagnostics.DiagnosticBarrierProbe
import dev.distillpod.android.diagnostics.Diagnostics
import dev.distillpod.android.diagnostics.RecordingDiagnosticFileForce
import dev.distillpod.android.work.DownloadNetwork
import dev.distillpod.android.work.NetworkAccess
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * #312: a background transcript whose episode audio is not on the phone failed for good with "Could not
 * reach the server" when Android blocked the app's network (#292) while it ran without its notification
 * (#301). It now waits queued for the network, like a background download, without holding back
 * transcripts whose audio is already local. Real failures still fail with their message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptionNetworkBlockTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val remote = EpisodeEntity("blocked-remote", "blocked-show", "guid-remote", "Remote episode",
        "https://example.test/remote.mp3", mediaFingerprint = "remote-audio", durationMs = 10000)
    private val local = EpisodeEntity("blocked-local", "blocked-show", "guid-local", "Local episode",
        "https://example.test/local.mp3", mediaFingerprint = "local-audio", durationMs = 10000)
    /**
     * The fixture's model file is not a real model: the first step after the audio, the model check,
     * fails with this. A run that ends with it got past the audio download.
     */
    private val pastAudio = "Download the transcription model in Settings first"

    /**
     * Android 15+ blocks the network of an app whose process is cached (#292): new requests fail at DNS
     * and the default network callback reports the network as blocked.
     */
    private class Firewall {
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val reported = MutableStateFlow<NetworkAccess?>(NetworkAccess(blocked = false, metered = false))
        fun close() = closed.set(true)
        fun block() { close(); reported.value = NetworkAccess(blocked = true, metered = false) }
        fun lift() { closed.set(false); reported.value = NetworkAccess(blocked = false, metered = false) }
    }

    private suspend fun fixture(block: suspend (AppGraph, MockWebServer, Firewall, Diagnostics, DiagnosticBarrierProbe) -> Unit) {
        context.getSharedPreferences("diagnostics", 0).edit().clear().commit()
        File(context.noBackupFilesDir, "diagnostics").deleteRecursively()
        File(context.noBackupFilesDir, "reliability").deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            // Scheduled requests stay unfinished; each test drives the real worker explicitly.
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(context: Context, name: String, params: WorkerParameters): ListenableWorker =
                    HeldTranscriptWorker(context, params)
            }).build())
        context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, 80)
            .putExtra(BatteryManager.EXTRA_SCALE, 100).putExtra(BatteryManager.EXTRA_PLUGGED, 0))
        val graph = AppGraph(context, CoroutineScope(SupervisorJob().apply { cancel() } + Dispatchers.Main.immediate))
        // TranscriptWorker resolves context.appGraph internally.
        ReflectionHelpers.setField(context, "graph\$delegate", lazyOf(graph))
        graph.ready.complete(Unit)
        val diagnostics = Diagnostics(context, "https://abc123@o1.ingest.sentry.io/1", RecordingDiagnosticFileForce())
        ReflectionHelpers.setField(graph, "diagnostics", diagnostics)
        val barrier = DiagnosticBarrierProbe(diagnostics)
        val firewall = Firewall()
        MockWebServer().use { server ->
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                if (firewall.closed.get()) throw java.net.UnknownHostException("Unable to resolve host")
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            ReflectionHelpers.setField(graph, "media",
                MediaRepository(context, graph.db, graph.settings, ResumableDownloader(client), graph.activeFiles))
            ReflectionHelpers.setField(graph, "downloadNetwork",
                DownloadNetwork({ firewall.reported }, reportMillis = 500, recheckMillis = 1000))
            val dao = graph.db.library()
            try {
                diagnostics.consent(true)
                graph.settings.flag("onlineTranscripts", false)
                graph.settings.flag("charging", false)
                graph.settings.flag("refresh", false)
                // Local transcription goes past the model check to the audio.
                File(graph.media.modelsDirectory, "tiny.bin").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
                dao.putModel(ModelEntity("tiny", "ready", 1))
                dao.putPodcast(PodcastEntity("blocked-show", "https://example.test/feed", "Show"))
                dao.putEpisode(remote)
                val audio = File(graph.media.directory, "blocked-local.audio").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
                dao.putEpisode(local.copy(localFile = audio.path, downloadState = "downloaded", downloadedBytes = audio.length()))
                block(graph, server, firewall, diagnostics, barrier)
            } finally {
                diagnostics.consent(false)
                WorkManager.getInstance(context).cancelAllWork().result.get(5, TimeUnit.SECONDS)
                graph.db.close()
                WorkManagerTestInitHelper.closeWorkDatabase()
            }
        }
    }

    private fun rejected(): ForegroundUpdater = ForegroundUpdater { _, _, _ ->
        CallbackToFutureAdapter.getFuture<Void> { it.setException(android.app.ForegroundServiceStartNotAllowedException("Synthetic background start")); "rejected" }
    }
    private fun allowed(): ForegroundUpdater = ForegroundUpdater { _, _, _ ->
        CallbackToFutureAdapter.getFuture<Void> { it.set(null); "allowed" }
    }
    private fun worker(episode: EpisodeEntity, revision: String, foreground: ForegroundUpdater) =
        TestListenableWorkerBuilder.from(context, TranscriptWorker::class.java)
            .setInputData(workDataOf("episode" to episode.id, "revision" to revision))
            .setForegroundUpdater(foreground).build()
    private suspend fun queue(graph: AppGraph, episode: EpisodeEntity): String {
        graph.transcription.prepare(episode.id)
        return graph.db.library().transcript(episode.id)!!.revision
    }
    private fun audioResponse() = MockResponse().setBody("x".repeat(5000)).setHeader("ETag", "\"v1\"")

    /** The waits queued for upload, per revision: their `operation.summary` since #313. */
    private fun queued(diagnostics: Diagnostics, barrier: DiagnosticBarrierProbe): Map<String, List<String>> {
        barrier.awaitIdle()
        val event = Regex(""""body"\s*:\s*"operation\.summary"""")
        return diagnostics.queue.files().map { it.readText() }.filter { event.containsMatchIn(it) }.mapNotNull { payload ->
            fun attribute(name: String) = Regex(""""$name"\s*:\s*\{[^}]*"value"\s*:\s*"([^"]+)"""").find(payload)!!.groupValues[1]
            if (attribute("feature") != "transcription" || attribute("outcome") !in setOf("queued", "waiting")) null
            else attribute("operation_id") to attribute("reason")
        }.groupBy({ it.first }, { it.second })
    }

    @Test fun `a background transcript whose audio download Android blocks waits queued and continues when the network is usable`() = runBlocking<Unit> {
        fixture { graph, server, firewall, diagnostics, barrier ->
            val dao = graph.db.library()
            val revision = queue(graph, remote)
            firewall.block()
            val run = async(Dispatchers.IO) { worker(remote, revision, rejected()).doWork() }
            // Longer than a recheck: the worker keeps waiting instead of failing or returning to WorkManager.
            delay(1500)
            assertTrue(run.isActive)
            val waiting = dao.transcript(remote.id)!!
            assertEquals(revision, waiting.revision)
            assertEquals("queued", waiting.state)
            assertNull(waiting.error)
            assertEquals(NETWORK_BLOCKED, graph.transcription.waitReasons.value[revision])
            // The wait reaches diagnostics too: an unknown reason would be dropped.
            assertTrue(NETWORK_BLOCKED in queued(diagnostics, barrier)[revision].orEmpty())
            assertEquals("queued", dao.episode(remote.id)!!.downloadState)
            assertEquals(0, server.requestCount)

            // A job binds the process again, or the user opens the app, and Android lifts the block.
            server.enqueue(audioResponse())
            firewall.lift()
            assertEquals(ListenableWorker.Result.failure(), withTimeout(10_000) { run.await() })
            assertEquals(1, server.requestCount)
            assertEquals("downloaded", dao.episode(remote.id)!!.downloadState)
            // The same run went on past the audio to the (fixture) model check.
            assertEquals(pastAudio, dao.transcript(remote.id)!!.error)
        }
    }

    @Test fun `a transcript waiting for the network does not hold back one whose audio is on the phone`() = runBlocking<Unit> {
        fixture { graph, _, firewall, _, _ ->
            val dao = graph.db.library()
            val waitingRevision = queue(graph, remote)
            val localRevision = queue(graph, local)
            firewall.block()
            val waiting = async(Dispatchers.IO) { worker(remote, waitingRevision, rejected()).doWork() }
            delay(1000)
            assertTrue(waiting.isActive)
            // The local transcript's own run reaches its audio and goes on while the other one waits.
            withTimeout(10_000) { worker(local, localRevision, rejected()).doWork() }
            assertEquals(pastAudio, dao.transcript(local.id)!!.error)
            assertTrue(waiting.isActive)
            assertEquals("queued", dao.transcript(remote.id)!!.state)
            assertEquals(NETWORK_BLOCKED, graph.transcription.waitReasons.value[waitingRevision])
            waiting.cancelAndJoin()
        }
    }

    @Test fun `a pause while the transcript waits for the network stays paused`() = runBlocking<Unit> {
        fixture { graph, server, firewall, _, _ ->
            val dao = graph.db.library()
            val revision = queue(graph, remote)
            firewall.block()
            val worker = worker(remote, revision, rejected())
            val run = launch(Dispatchers.IO) { worker.doWork() }
            delay(1000)
            assertTrue(run.isActive)
            graph.transcription.pause(remote.id)
            worker.stop(WorkInfo.STOP_REASON_CANCELLED_BY_APP)
            run.cancelAndJoin()
            firewall.lift()
            delay(500)
            assertEquals("paused", dao.transcript(remote.id)!!.state)
            assertNull(graph.transcription.waitReasons.value[revision])
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `a real download failure still fails the transcript with its message`() = runBlocking<Unit> {
        fixture { graph, server, firewall, _, _ ->
            val dao = graph.db.library()
            // A real outage in the background: requests fail, Android reports no block.
            var revision = queue(graph, remote)
            firewall.close()
            assertEquals(ListenableWorker.Result.failure(), withTimeout(10_000) { worker(remote, revision, rejected()).doWork() })
            assertEquals("failed", dao.transcript(remote.id)!!.state)
            assertEquals(safeFailure(java.net.UnknownHostException()), dao.transcript(remote.id)!!.error)

            // With its foreground service the network is the app's: a failure is real even when a block is reported.
            graph.transcription.prepare(remote.id)
            revision = dao.transcript(remote.id)!!.revision
            firewall.block()
            assertEquals(ListenableWorker.Result.failure(), withTimeout(10_000) { worker(remote, revision, allowed()).doWork() })
            assertEquals("failed", dao.transcript(remote.id)!!.state)
            assertEquals(safeFailure(java.net.UnknownHostException()), dao.transcript(remote.id)!!.error)

            // The server answers with an error.
            firewall.lift()
            graph.transcription.prepare(remote.id)
            revision = dao.transcript(remote.id)!!.revision
            server.enqueue(MockResponse().setResponseCode(404))
            assertEquals(ListenableWorker.Result.failure(), withTimeout(10_000) { worker(remote, revision, rejected()).doWork() })
            assertEquals("failed", dao.transcript(remote.id)!!.state)
            assertEquals(safeFailure(java.io.IOException("Audio download failed (404)")), dao.transcript(remote.id)!!.error)
            assertEquals(1, server.requestCount)
        }
    }
}

private class HeldTranscriptWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val completion = CompletableDeferred<Result>()
    override suspend fun doWork(): Result = completion.await()
}
