package dev.distillpod.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.android.diagnostics.DiagnosticBarrierProbe
import dev.distillpod.android.diagnostics.Diagnostics
import dev.distillpod.android.diagnostics.RecordingDiagnosticFileForce
import dev.distillpod.android.work.LibraryWorker
import io.sentry.Sentry
import kotlinx.coroutines.*
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
 * #294: a feed that answers an HTTP error, is unreadable or cannot be reached is the publisher's or the
 * network's state. The Library already names it; it is not an internal app failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeedRefreshDiagnosticsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private suspend fun fixture(block: suspend (AppGraph, Diagnostics, DiagnosticBarrierProbe) -> Unit) {
        context.getSharedPreferences("diagnostics", 0).edit().clear().commit()
        File(context.noBackupFilesDir, "diagnostics").deleteRecursively()
        File(context.noBackupFilesDir, "reliability").deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val graph = AppGraph(context, CoroutineScope(SupervisorJob().apply { cancel() } + Dispatchers.Main.immediate))
        // LibraryWorker resolves context.appGraph internally.
        ReflectionHelpers.setField(context, "graph\$delegate", lazyOf(graph))
        val diagnostics = Diagnostics(context, "https://abc123@o1.ingest.sentry.io/1", RecordingDiagnosticFileForce())
        ReflectionHelpers.setField(graph, "diagnostics", diagnostics)
        val barrier = DiagnosticBarrierProbe(diagnostics)
        graph.ready.complete(Unit)
        try {
            diagnostics.consent(true)
            block(graph, diagnostics, barrier)
        } finally {
            diagnostics.consent(false)
            WorkManager.getInstance(context).cancelAllWork().result.get(5, TimeUnit.SECONDS)
            graph.db.close()
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }

    private suspend fun refresh(id: String) = TestListenableWorkerBuilder.from(context, LibraryWorker::class.java)
        .setInputData(workDataOf("kind" to "refresh", "id" to id)).build().doWork()

    private fun payloads(diagnostics: Diagnostics, barrier: DiagnosticBarrierProbe): List<String> {
        barrier.awaitIdle(); Sentry.flush(2000)
        return diagnostics.queue.files().map { it.readText() }
    }
    private fun workerFailures(payloads: List<String>) = payloads.filter { Regex(""""body"\s*:\s*"worker\.failed"""").containsMatchIn(it) }
        .map { Regex(""""reason"\s*:\s*\{[^}]*"value"\s*:\s*"([^"]+)"""").find(it)!!.groupValues[1] }

    @Test fun `feed server, unreadable feed and connection failures are not internal app failures`() = runBlocking<Unit> {
        fixture { graph, diagnostics, barrier ->
            val dao = graph.db.library()
            MockWebServer().use { server ->
                dao.putPodcast(PodcastEntity("http-error", server.url("/missing").toString(), "Missing"))
                dao.putPodcast(PodcastEntity("unreadable", server.url("/page").toString(), "Unreadable"))
                server.enqueue(MockResponse().setResponseCode(404))
                server.enqueue(MockResponse().setBody("<html><body>Not a feed</body>"))
                assertEquals(ListenableWorker.Result.failure(), refresh("http-error"))
                assertEquals(ListenableWorker.Result.failure(), refresh("unreadable"))
                val closed = server.url("/gone").toString()
                server.shutdown()
                dao.putPodcast(PodcastEntity("offline", closed, "Offline"))
                assertEquals(ListenableWorker.Result.failure(), refresh("offline"))
            }
            // The Library's explanation is unchanged.
            assertEquals("The feed address no longer works (HTTP 404).", dao.podcast("http-error")!!.error)
            assertEquals("The feed couldn't be read. It may not be a valid podcast feed.", dao.podcast("unreadable")!!.error)
            assertEquals("Couldn't reach the feed. Check your connection and try again.", dao.podcast("offline")!!.error)
            val payloads = payloads(diagnostics, barrier)
            assertFalse(payloads.joinToString(), payloads.any { it.contains("Diagnostic failure") })
            assertEquals(listOf("publisher", "invalid_data", "network_or_storage"), workerFailures(payloads))
        }
    }

    @Test fun `an unexpected refresh error is still an app failure`() = runBlocking<Unit> {
        fixture { graph, diagnostics, barrier ->
            val dao = graph.db.library()
            // Subscription validation refuses embedded credentials, so a stored one is unexpected.
            dao.putPodcast(PodcastEntity("broken", "https://user:secret@feeds.example.test/rss", "Broken"))
            assertEquals(ListenableWorker.Result.failure(), refresh("broken"))
            assertEquals("The feed couldn't be refreshed.", dao.podcast("broken")!!.error)
            val payloads = payloads(diagnostics, barrier)
            assertEquals(listOf("invalid_data"), workerFailures(payloads))
            assertTrue(payloads.any { it.contains("Diagnostic failure: invalid_data") })
            assertFalse(payloads.any { it.contains("secret") })
        }
    }
}
