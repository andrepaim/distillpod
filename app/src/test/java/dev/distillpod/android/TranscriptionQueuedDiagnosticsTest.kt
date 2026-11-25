package dev.distillpod.android

import android.content.Context
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
 * #294: each rescheduling pass re-logged `transcription.queued` for every waiting revision, and the
 * foreground session's own stop recovery logged each one again, spending the routine log allowance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptionQueuedDiagnosticsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun episode(n: Int) = EpisodeEntity("queued-episode-$n", "queued-show", "guid-$n", "Episode $n",
        "https://example.test/$n.mp3", mediaFingerprint = "audio-$n", durationMs = 10000)

    private suspend fun fixture(block: suspend (AppGraph, Diagnostics, DiagnosticBarrierProbe) -> Unit) {
        context.getSharedPreferences("diagnostics", 0).edit().clear().commit()
        File(context.noBackupFilesDir, "diagnostics").deleteRecursively()
        File(context.noBackupFilesDir, "reliability").deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        // Explicit repository calls only: no startup recovery or worker runs.
        val graph = AppGraph(context, CoroutineScope(SupervisorJob().apply { cancel() } + Dispatchers.Main.immediate))
        val diagnostics = Diagnostics(context, "https://abc123@o1.ingest.sentry.io/1", RecordingDiagnosticFileForce())
        ReflectionHelpers.setField(graph, "diagnostics", diagnostics)
        val barrier = DiagnosticBarrierProbe(diagnostics)
        try {
            diagnostics.consent(true)
            graph.settings.flag("onlineTranscripts", false)
            graph.settings.flag("charging", true) // Robolectric reports no charger: every row waits.
            graph.db.library().putPodcast(PodcastEntity("queued-show", "https://example.test/feed", "Show"))
            for (n in 1..3) graph.db.library().putEpisode(episode(n))
            block(graph, diagnostics, barrier)
        } finally { diagnostics.consent(false); graph.db.close() }
    }

    /** The reasons of the `transcription.queued` logs queued for upload, per revision. */
    private fun queued(diagnostics: Diagnostics, barrier: DiagnosticBarrierProbe, name: String = "transcription.queued"): Map<String, List<String>> {
        barrier.awaitIdle()
        val event = Regex(""""body"\s*:\s*"${Regex.escape(name)}"""")
        return diagnostics.queue.files().map { it.readText() }.filter { event.containsMatchIn(it) }.map { payload ->
            fun attribute(name: String) = Regex(""""$name"\s*:\s*\{[^}]*"value"\s*:\s*"([^"]+)"""").find(payload)!!.groupValues[1]
            attribute("operation_id") to attribute("reason")
        }.groupBy({ it.first }, { it.second })
    }

    /** One foreground-session pass: the waiting rows are rescheduled, then its stop recovers them again. */
    private suspend fun sessionPass(graph: AppGraph) {
        assertNull(graph.transcription.nextForeground())
        graph.transcription.recover()
    }

    @Test fun `rescheduling unchanged waiting transcriptions logs each wait once`() = runBlocking<Unit> {
        fixture { graph, diagnostics, barrier ->
            val dao = graph.db.library()
            graph.transcription.prepare(episode(1).id)
            graph.transcription.prepare(episode(2).id)
            sessionPass(graph)
            // Another episode queued later reschedules the ones already waiting.
            graph.transcription.prepare(episode(3).id)
            sessionPass(graph)
            sessionPass(graph)
            val revisions = (1..3).map { dao.transcript(episode(it).id)!!.revision }
            assertEquals(revisions.associateWith { listOf("charging_required") }, queued(diagnostics, barrier))
        }
    }

    @Test fun `a real transition logs the new wait once`() = runBlocking<Unit> {
        fixture { graph, diagnostics, barrier ->
            val id = episode(1).id
            graph.transcription.prepare(id)
            sessionPass(graph)
            val revision = graph.db.library().transcript(id)!!.revision
            // Pause and Resume keep the revision; the resumed wait is a new state.
            graph.transcription.pause(id)
            graph.transcription.prepare(id)
            assertEquals(revision, graph.db.library().transcript(id)!!.revision)
            sessionPass(graph)
            assertEquals(mapOf(revision to listOf("charging_required", "charging_required")), queued(diagnostics, barrier))
            // A changed reason is a change too.
            graph.settings.flag("charging", false)
            graph.transcription.recover()
            graph.transcription.recover()
            val reasons = queued(diagnostics, barrier).getValue(revision)
            assertEquals(3, reasons.size)
            assertNotEquals("charging_required", reasons.last())
        }
    }

    @Test fun `a refused foreground start runs on and is logged as a stage, not a wait`() = runBlocking<Unit> {
        fixture { graph, diagnostics, barrier ->
            val dao = graph.db.library()
            val row = TranscriptEntity(episode(1).id, java.util.UUID.randomUUID().toString(), episode(1).mediaFingerprint, "tiny", "auto")
            dao.putTranscript(row)
            assertEquals(TranscriptOutcome.COMPLETED, graph.transcription.process(row.episodeId, row.revision,
                TranscriptExecution({ false }, { throw android.app.ForegroundServiceStartNotAllowedException("Synthetic") }, true)))
            // The chunk went on to the model check (none is installed), so it waits for that, not for Android (#301).
            assertEquals(mapOf(row.revision to listOf("checking_model")), queued(diagnostics, barrier))
            assertEquals("restricted", queued(diagnostics, barrier, "transcription.stage").getValue(row.revision).first())
            assertNotEquals("restricted", graph.transcription.waitReasons.value[row.revision])
        }
    }
}
