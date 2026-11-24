package dev.distillpod.android

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.android.data.*
import dev.distillpod.android.diagnostics.DiagnosticBarrierProbe
import dev.distillpod.android.diagnostics.Diagnostics
import dev.distillpod.android.diagnostics.RecordingDiagnosticFileForce
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File

/**
 * #299: transcripts run one at a time, and the queue had no order, so an explicit Transcribe could wait
 * behind automatic transcripts while its row said "waiting for power or network".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptionQueueOrderTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    // Downloaded audio: local transcription needs no network, so only the queue decides.
    private fun episode(n: Int) = EpisodeEntity("order-episode-$n", "order-show", "guid-$n", "Episode $n",
        "https://example.test/$n.mp3", mediaFingerprint = "audio-$n", durationMs = 10000,
        localFile = File(context.cacheDir, "order-$n.mp3").apply { writeBytes(byteArrayOf(1)) }.path)

    private suspend fun fixture(block: suspend (AppGraph, Diagnostics, DiagnosticBarrierProbe) -> Unit) {
        context.getSharedPreferences("diagnostics", 0).edit().clear().commit()
        File(context.noBackupFilesDir, "diagnostics").deleteRecursively()
        File(context.noBackupFilesDir, "reliability").deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, 80)
            .putExtra(BatteryManager.EXTRA_SCALE, 100).putExtra(BatteryManager.EXTRA_PLUGGED, 0))
        // Explicit repository calls only: no startup recovery or worker runs.
        val graph = AppGraph(context, CoroutineScope(SupervisorJob().apply { cancel() } + Dispatchers.Main.immediate))
        val diagnostics = Diagnostics(context, "https://abc123@o1.ingest.sentry.io/1", RecordingDiagnosticFileForce())
        ReflectionHelpers.setField(graph, "diagnostics", diagnostics)
        val barrier = DiagnosticBarrierProbe(diagnostics)
        try {
            diagnostics.consent(true)
            graph.settings.flag("onlineTranscripts", false)
            graph.settings.flag("charging", false)
            graph.db.library().putPodcast(PodcastEntity("order-show", "https://example.test/feed", "Show"))
            for (n in 1..3) graph.db.library().putEpisode(episode(n))
            block(graph, diagnostics, barrier)
        } finally { diagnostics.consent(false); graph.db.close() }
    }

    private suspend fun revision(graph: AppGraph, n: Int) = graph.db.library().transcript(episode(n).id)!!.revision

    /** The reasons of the `transcription.queued` logs queued for upload, per revision. */
    private fun queued(diagnostics: Diagnostics, barrier: DiagnosticBarrierProbe): Map<String, List<String>> {
        barrier.awaitIdle()
        val event = Regex(""""body"\s*:\s*"transcription\.queued"""")
        return diagnostics.queue.files().map { it.readText() }.filter { event.containsMatchIn(it) }.map { payload ->
            fun attribute(name: String) = Regex(""""$name"\s*:\s*\{[^}]*"value"\s*:\s*"([^"]+)"""").find(payload)!!.groupValues[1]
            attribute("operation_id") to attribute("reason")
        }.groupBy({ it.first }, { it.second })
    }

    @Test fun `an explicit request goes ahead of automatic transcripts, which wait behind it`() = runBlocking<Unit> {
        fixture { graph, diagnostics, barrier ->
            val transcription = graph.transcription
            transcription.prepare(episode(1).id)
            transcription.prepare(episode(2).id)
            transcription.prepare(episode(3).id, requested = true)
            assertEquals(episode(3).id, transcription.nextForeground()?.episodeId)
            val behind = listOf(revision(graph, 1), revision(graph, 2))
            assertEquals(behind.associateWith { "queued_behind" }, transcription.waitReasons.value.filterKeys { it in behind })
            // The wait reaches diagnostics too: an unknown reason would be dropped.
            assertEquals(behind.associateWith { listOf("queued_behind") }, queued(diagnostics, barrier).filterKeys { it in behind })
        }
    }

    @Test fun `explicit requests run in request order, then automatic transcripts oldest first`() = runBlocking<Unit> {
        fixture { graph, _, _ ->
            val transcription = graph.transcription
            transcription.prepare(episode(1).id)
            transcription.prepare(episode(2).id)
            transcription.prepare(episode(3).id)
            assertEquals(episode(1).id, transcription.nextForeground()?.episodeId)
            // Saving a moment in an automatic transcript's episode asks for it too.
            transcription.prepare(episode(3).id, allowOnline = false, requested = true)
            transcription.prepare(episode(2).id, requested = true)
            transcription.prepare(episode(3).id, requested = true) // Asking again keeps its place.
            assertEquals(episode(3).id, transcription.nextForeground()?.episodeId)
            transcription.pause(episode(3).id)
            assertEquals(episode(2).id, transcription.nextForeground()?.episodeId)
            transcription.pause(episode(2).id)
            assertEquals(episode(1).id, transcription.nextForeground()?.episodeId)
        }
    }

    @Test fun `an explicit request waits for the chunk in progress instead of interrupting it`() = runBlocking<Unit> {
        fixture { graph, _, _ ->
            val dao = graph.db.library()
            val transcription = graph.transcription
            transcription.prepare(episode(1).id)
            val running = revision(graph, 1)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val chunk = launch(Dispatchers.IO) {
                transcription.process(episode(1).id, running, TranscriptExecution({ false }, { started.complete(Unit); release.await() }, false))
            }
            withTimeout(10_000) { started.await() }
            transcription.prepare(episode(2).id, requested = true)
            assertTrue(chunk.isActive)
            assertEquals("processing", dao.transcript(episode(1).id)!!.state)
            assertEquals(running, dao.transcript(episode(1).id)!!.revision)
            assertEquals("queued_behind", transcription.waitReasons.value[revision(graph, 2)])
            release.complete(Unit)
            withTimeout(10_000) { chunk.join() }
            // The chunk finished on its own terms (no model is installed here), not by cancellation.
            assertEquals("waiting_model", dao.transcript(episode(1).id)!!.state)
            assertEquals(episode(2).id, transcription.nextForeground()?.episodeId)
        }
    }

    @Test fun `a refused foreground start is the recorded wait`() = runBlocking<Unit> {
        fixture { graph, _, _ ->
            val transcription = graph.transcription
            transcription.prepare(episode(1).id)
            val revision = revision(graph, 1)
            assertEquals(TranscriptOutcome.DEFERRED_START, transcription.process(episode(1).id, revision,
                TranscriptExecution({ false }, { throw android.app.ForegroundServiceStartNotAllowedException("Synthetic") }, true, 0)))
            assertEquals("restricted", transcription.waitReasons.value[revision])
            // A start Android allows, with nothing else running, no longer waits for anything.
            transcription.prepare(episode(1).id, requested = true)
            assertEquals(revision, revision(graph, 1))
            assertNull(transcription.waitReasons.value[revision])
            // Leaving the queue clears a wait too.
            assertEquals(episode(1).id, transcription.nextForeground()?.episodeId)
            transcription.prepare(episode(2).id)
            assertEquals(episode(1).id, transcription.nextForeground()?.episodeId)
            assertEquals("queued_behind", transcription.waitReasons.value[revision(graph, 2)])
            transcription.pause(episode(2).id)
            assertNull(transcription.waitReasons.value[revision(graph, 2)])
        }
    }

    @Test fun `power and network waits are recorded per transcript`() = runBlocking<Unit> {
        fixture { graph, _, _ ->
            val transcription = graph.transcription
            graph.settings.flag("charging", true) // Robolectric reports no charger.
            transcription.prepare(episode(1).id)
            transcription.prepare(episode(2).id, requested = true)
            assertNull(transcription.nextForeground())
            assertEquals(listOf(1, 2).map { revision(graph, it) }.associateWith { "charging_required" },
                transcription.waitReasons.value.filterKeys { it in listOf(revision(graph, 1), revision(graph, 2)) })
        }
    }
}
