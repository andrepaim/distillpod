package dev.distillpod.android.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.sentry.Sentry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * #313: a phone spent its 800 routine lines a day in 96 minutes of background transcription. Every
 * stage of a two-minute window was logged twice, and every process start logged each transcript
 * waiting for a charger three more times.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class RoutineVolumeTest {
    private lateinit var context:Context
    private val dsn="https://abc123@o1.ingest.sentry.io/1"
    private val started=mutableListOf<Diagnostics>()
    private fun id()=UUID.randomUUID().toString()
    private fun correlation(revision:String,run:String?=null)=DiagnosticCorrelation(transcriptRevisionId=revision,executionId=run)

    @Before fun setup() {
        context=ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("diagnostics",0).edit().clear().commit()
        File(context.noBackupFilesDir,"diagnostics").deleteRecursively()
        File(context.noBackupFilesDir,"reliability").deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }
    @After fun finish() {started.lastOrNull()?.consent(false);Sentry.close()}

    /** A process start: a new instance over the same outbox, journal and preferences. */
    private fun start():Pair<Diagnostics,DiagnosticBarrierProbe> {
        Sentry.close()
        val diagnostics=Diagnostics(context,dsn,RecordingDiagnosticFileForce())
        started+=diagnostics
        return diagnostics to DiagnosticBarrierProbe(diagnostics).also {it.awaitIdle()}
    }

    private data class Line(val file:String,val name:String,val routine:Boolean,val text:String) {
        fun attribute(key:String)=Regex(""""$key"\s*:\s*\{[^}]*"value"\s*:\s*"?([^",}]+)""").find(text)?.groupValues?.get(1)
        override fun toString()=listOfNotNull(name,attribute("outcome"),attribute("reason")).joinToString(" ")
    }
    private fun lines(diagnostics:Diagnostics):List<Line> = diagnostics.queue.files().map {file->
        val text=file.readText()
        // An exception envelope has no log body.
        Line(file.name,Regex(""""body"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1) ?: "exception",!file.name.contains("priority-"),text)
    }
    /** Lines stored after [before], other than the start's own `app.started` and health report. */
    private fun added(diagnostics:Diagnostics,before:Set<String>)=lines(diagnostics)
        .filter {it.file !in before && it.name !in setOf("app.started","diagnostics.health")}

    /** The events TranscriptionRepository.processChunk reports for one two-minute WorkManager window. */
    private fun window(d:Diagnostics,revision:String,refused:Boolean=true):String {
        val run=id()
        d.event("transcription.started",revision,attempt=run,correlation=correlation(revision,run))
        if(refused)d.event("transcription.stage",revision,reason="restricted",correlation=correlation(revision,run))
        for(stage in listOf("downloading","checking_model","decoding","starting","transcribing","saving"))
            d.event("transcription.stage",revision,reason=stage,correlation=correlation(revision,run))
        // The next window is enqueued, then the finally block publishes the queued progress.
        d.event("transcription.queued",revision,reason="scheduled",correlation=correlation(revision))
        d.event("transcription.stage",revision,reason="queued",correlation=correlation(revision,run))
        return run
    }

    @Test fun `a background transcript window logs its start, the refused start and its outcome`() {
        val (d,barrier)=start()
        d.consent(true);barrier.awaitIdle()
        val revision=id()
        val before=lines(d).map {it.file}.toSet()
        val run=window(d,revision);barrier.awaitIdle()
        val window=added(d,before)
        println("#313 routine lines per window: ${window.count {it.routine}} $window")
        // Outbox file names order lines only to the millisecond.
        assertEquals(listOf("operation.summary queued scheduled","operation.summary running none","transcription.stage restricted"),
            window.map {it.toString()}.sorted())
        assertTrue(window.all {it.routine})
        // Which window: both summaries carry the window's attempt.
        assertTrue(window.filter {it.name=="operation.summary"}.all {it.attribute("attempt_id")==run})
        // A window whose foreground start was allowed has no restricted line.
        val allowed=lines(d).map {it.file}.toSet()
        window(d,revision,refused=false);barrier.awaitIdle()
        assertEquals(2,added(d,allowed).count {it.routine})
    }

    @Test fun `a window that fails keeps its failure reason and separate allowance`() {
        val (d,barrier)=start()
        d.consent(true);barrier.awaitIdle()
        val revision=id();val run=id()
        val before=lines(d).map {it.file}.toSet()
        d.event("transcription.started",revision,attempt=run,correlation=correlation(revision,run))
        d.event("transcription.stage",revision,reason="restricted",correlation=correlation(revision,run))
        d.event("transcription.stage",revision,reason="downloading",correlation=correlation(revision,run))
        d.failure("transcription",java.net.SocketTimeoutException("Synthetic"),revision,correlation=correlation(revision,run))
        barrier.awaitIdle();Sentry.flush(2000)
        val window=added(d,before)
        val terminal=window.filter {!it.routine}.map {it.toString()}
        assertTrue(terminal.toString(),"transcription.failed timeout" in terminal)
        assertTrue(terminal.toString(),"operation.summary failed timeout" in terminal)
        assertEquals(2,window.count {it.routine})
    }

    /** Eleven transcripts wait for a charger and one chunk is running when the process dies. */
    private fun waitingFleet(d:Diagnostics,barrier:DiagnosticBarrierProbe):Pair<List<String>,String> {
        val waiting=List(11) {id()}
        for(revision in waiting) {
            // Each was last observed by its own window's finally block, with that window's execution.
            val run=id()
            d.event("transcription.started",revision,attempt=run,correlation=correlation(revision,run))
            d.event("transcription.queued",revision,reason="charging_required",correlation=correlation(revision,run))
        }
        val running=id();val run=id()
        d.event("transcription.started",running,attempt=run,correlation=correlation(running,run))
        d.event("transcription.stage",running,reason="transcribing",correlation=correlation(running,run))
        barrier.awaitIdle()
        return waiting to running
    }
    /** What startup does: reconcile the journal with Room, then TranscriptionRepository.recover(). */
    private fun recover(d:Diagnostics,barrier:DiagnosticBarrierProbe,revisions:List<String>) {
        d.reconcile(revisions.associate {("transcription" to it) to "queued"})
        for(revision in revisions)d.event("transcription.queued",revision,reason="charging_required",correlation=correlation(revision))
        barrier.awaitIdle()
    }

    @Test fun `a restart does not log again or call interrupted a transcript still waiting for the same reason`() {
        val (first,firstBarrier)=start()
        first.consent(true);firstBarrier.awaitIdle()
        val (waiting,running)=waitingFleet(first,firstBarrier)

        val (d,barrier)=start()
        val before=lines(d).map {it.file}.toSet()
        recover(d,barrier,waiting+running)
        val restart=added(d,before)
        println("#313 lines per process start with 11 waiting and 1 running: ${restart.size} " +
            "(${restart.count {it.routine}} routine) $restart")
        val ofWaiting=restart.filter {line->waiting.any {line.text.contains(it)}}
        assertEquals(emptyList<Line>(),ofWaiting)
        // The chunk that was really running is still reported as interrupted, once.
        val ofRunning=restart.filter {it.text.contains(running)}.map {it.toString()}
        assertEquals(listOf("operation.summary interrupted process_interrupted","operation.summary queued process_interrupted",
            "operation.summary waiting charging_required"),ofRunning.sorted())
        assertTrue(d.journal.latest().filter {it.operation in waiting}.all {it.state=="waiting" && it.reason=="charging_required"})

        // Later starts log nothing for any of them.
        val (again,againBarrier)=start()
        val quiet=lines(again).map {it.file}.toSet()
        recover(again,againBarrier,waiting+running)
        assertEquals(emptyList<Line>(),added(again,quiet))
    }

    @Test fun `a changed wait reason is still logged once after a restart`() {
        val (first,firstBarrier)=start()
        first.consent(true);firstBarrier.awaitIdle()
        val revision=id()
        first.event("transcription.queued",revision,reason="charging_required",correlation=correlation(revision));firstBarrier.awaitIdle()
        val (d,barrier)=start()
        val before=lines(d).map {it.file}.toSet()
        d.reconcile(mapOf(("transcription" to revision) to "queued"))
        repeat(2) {d.event("transcription.queued",revision,reason="wifi_required",correlation=correlation(revision))}
        barrier.awaitIdle()
        assertEquals(listOf("operation.summary waiting wifi_required"),added(d,before).map {it.toString()})
    }

    /**
     * The field evidence: the lines a start stored after its first upload run were uploaded only by the
     * next start, 0.2 to 8 seconds after its `app.started`, sooner than a request of its own could run:
     * the request left by the previous process was already due. The re-logs below are what was stored after the run: they
     * enqueue a new upload request, and a process that dies before it runs is started again for it.
     */
    @Test fun `a start that finds only unchanged waits leaves no upload request behind`() {
        val (first,firstBarrier)=start()
        first.consent(true);firstBarrier.awaitIdle()
        val (waiting,running)=waitingFleet(first,firstBarrier)
        // The running chunk's interruption is reported by the first restart.
        val (settle,settleBarrier)=start();recover(settle,settleBarrier,waiting+running)

        val manager=WorkManager.getInstance(context)
        repeat(3) {
            val (d,barrier)=start()
            // The due request that launched this process runs at once and empties the outbox...
            assertEquals(DiagnosticUploader.Next.Done,d.uploader {DiagnosticDelivery.Receipt(DiagnosticDelivery.Outcome.ACCEPTED)}.drain {true})
            manager.cancelUniqueWork(Diagnostics.UPLOAD_WORK).result.get(5,TimeUnit.SECONDS)
            // ...before startup recovery reports anything.
            recover(d,barrier,waiting+running)
            assertEquals(emptyList<Line>(),lines(d))
            assertTrue(manager.getWorkInfosForUniqueWork(Diagnostics.UPLOAD_WORK).get(5,TimeUnit.SECONDS).all {it.state.isFinished})
        }
    }
}
