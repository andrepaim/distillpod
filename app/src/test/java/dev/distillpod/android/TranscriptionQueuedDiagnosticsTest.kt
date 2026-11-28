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
import io.sentry.Sentry
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

    private fun attribute(payload: String, name: String) =
        Regex(""""$name"\s*:\s*\{[^}]*"value"\s*:\s*"([^"]+)"""").find(payload)?.groupValues?.get(1)
    private fun logs(diagnostics: Diagnostics, barrier: DiagnosticBarrierProbe, name: String): List<String> {
        barrier.awaitIdle()
        val event = Regex(""""body"\s*:\s*"${Regex.escape(name)}"""")
        return diagnostics.queue.files().map { it.readText() }.filter { event.containsMatchIn(it) }
    }
    /** The reasons of the raw [name] logs queued for upload, per revision. */
    private fun raw(diagnostics: Diagnostics, barrier: DiagnosticBarrierProbe, name: String): Map<String, List<String>> =
        logs(diagnostics, barrier, name).groupBy({ attribute(it, "operation_id")!! }, { attribute(it, "reason")!! })
    /**
     * The waits queued for upload, per revision. Since #313 a wait is reported by its `operation.summary`
     * (outcome queued or waiting) only; the raw `transcription.queued` log repeated it.
     */
    private fun queued(diagnostics: Diagnostics, barrier: DiagnosticBarrierProbe): Map<String, List<String>> =
        logs(diagnostics, barrier, "operation.summary").filter { attribute(it, "feature") == "transcription" &&
            attribute(it, "outcome") in setOf("queued", "waiting") }
            .groupBy({ attribute(it, "operation_id")!! }, { attribute(it, "reason")!! })

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

    @Test fun `a process start logs nothing for transcripts still waiting for the same reason`() = runBlocking<Unit> {
        fixture { graph, diagnostics, barrier ->
            for (n in 1..3) graph.transcription.prepare(episode(n).id)
            sessionPass(graph)
            barrier.awaitIdle()
            val before = diagnostics.queue.files().map { it.name }.toSet()
            // #313: a new process has a new diagnostics instance and repository over the same Room
            // database, journal and outbox. Startup reconciles the journal, then recovers the queue.
            Sentry.close()
            val restarted = Diagnostics(context, "https://abc123@o1.ingest.sentry.io/1", RecordingDiagnosticFileForce())
            ReflectionHelpers.setField(graph, "diagnostics", restarted)
            val restartedBarrier = DiagnosticBarrierProbe(restarted)
            graph.reconcileJournal()
            val repository = TranscriptionRepository(graph)
            // Startup applies the settings to queued requests first, which refreshes their waits too (#336).
            repository.configureNetworkPolicy()
            repository.recover()
            restartedBarrier.awaitIdle()
            val added = restarted.queue.files().filter { it.name !in before }.map { it.readText() }
                .filterNot { Regex(""""body"\s*:\s*"(app.started|diagnostics.health)"""").containsMatchIn(it) }
            assertEquals(emptyList<String>(), added.map { "${Regex(""""body"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1)} ${attribute(it, "reason")}" })
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

    /** The routine lines added since [before] (file names), other than the start and health lines. */
    private fun added(diagnostics: Diagnostics, barrier: DiagnosticBarrierProbe, before: Set<String>): List<String> {
        barrier.awaitIdle()
        return diagnostics.queue.files().filter { it.name !in before }.map { it.readText() }
            .filterNot { Regex(""""body"\s*:\s*"(app.started|diagnostics.health)"""").containsMatchIn(it) }
            .map { "${Regex(""""body"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1)} ${attribute(it, "reason")}" }
    }

    /** #336: a settings change refreshes the wait each queued row shows; only a wait that changed is reported, once. */
    @Test fun `a settings change logs each changed wait once and an unchanged one not at all`() = runBlocking<Unit> {
        fixture { graph, diagnostics, barrier ->
            val dao = graph.db.library()
            for (n in 1..3) graph.transcription.prepare(episode(n).id)
            sessionPass(graph)
            val revisions = (1..3).map { dao.transcript(episode(it).id)!!.revision }
            assertEquals(revisions.associateWith { listOf("charging_required") }, queued(diagnostics, barrier))
            barrier.awaitIdle()
            var before = diagnostics.queue.files().map { it.name }.toSet()
            // The charger comes first, so the Wi-Fi setting changes no wait: nothing is written at all.
            graph.settings.flag("wifi", true)
            graph.transcription.configureNetworkPolicy()
            graph.transcription.configureNetworkPolicy()
            assertEquals(emptyList<String>(), added(diagnostics, barrier, before))
            before = diagnostics.queue.files().map { it.name }.toSet()
            graph.settings.flag("charging", false)
            graph.transcription.configureNetworkPolicy()
            graph.transcription.configureNetworkPolicy() // Applying the same settings again changes nothing.
            val lines = added(diagnostics, barrier, before)
            assertEquals(lines.toString(), 3, lines.size)
            val reasons = queued(diagnostics, barrier)
            for (revision in revisions) {
                assertEquals(2, reasons.getValue(revision).size)
                assertNotEquals("charging_required", reasons.getValue(revision).last())
            }
            assertEquals(emptyMap<String, List<String>>(), raw(diagnostics, barrier, "transcription.queued"))
        }
    }

    /** #336: the wait of the rows behind a transcript is refreshed at each of its chunks, but not reported again. */
    @Test fun `chunk boundaries do not log the unchanged wait of the transcripts behind`() = runBlocking<Unit> {
        fixture { graph, diagnostics, barrier ->
            val dao = graph.db.library()
            graph.settings.flag("charging", false)
            graph.transcription.prepare(episode(1).id)
            graph.transcription.prepare(episode(2).id)
            val first = dao.transcript(episode(1).id)!!.revision
            val behind = dao.transcript(episode(2).id)!!.revision
            repeat(3) {
                // A stopped chunk goes back to the queue, as a WorkManager stop does.
                try { graph.transcription.process(episode(1).id, first, TranscriptExecution({ true }, {}, false)) }
                catch (_: CancellationException) {}
                graph.transcription.nextForeground()
            }
            assertEquals(1, queued(diagnostics, barrier).getValue(behind).size)
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
            assertEquals(listOf("restricted"), raw(diagnostics, barrier, "transcription.stage").getValue(row.revision))
            assertEquals(emptyMap<String, List<String>>(), raw(diagnostics, barrier, "transcription.queued"))
            assertNotEquals("restricted", graph.transcription.waitReasons.value[row.revision])
        }
    }
}
