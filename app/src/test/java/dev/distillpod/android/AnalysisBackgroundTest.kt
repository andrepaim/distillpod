package dev.distillpod.android

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.android.ai.AiGateway
import dev.distillpod.android.ai.AiText
import dev.distillpod.android.data.*
import dev.distillpod.android.work.UnderstandingWorker
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Summary & chapters keep going after the user leaves the app (#84): the worker runs in the foreground, and a
 * system stop (job time limit, network, quota) resumes from the saved parts instead of waiting for Resume.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnalysisBackgroundTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val manager get() = WorkManager.getInstance(context)

    /** Answers every part; the call numbered [holdAt] waits until the worker is stopped. */
    private class Provider : AiGateway {
        val calls = AtomicInteger()
        @Volatile var holdAt = 0
        @Volatile var held = CompletableDeferred<Unit>()
        override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit): AiText {
            if (calls.incrementAndGet() == holdAt) { held.complete(Unit); awaitCancellation() }
            if (instructions.startsWith("Combine")) return AiText("""{"summary":"Practice improves skills over time."}""", "fixture")
            val first = JSONArray(input).getJSONObject(0)
            return AiText(JSONObject().put("summary", "Practice improves skills over time.")
                .put("chapters", JSONArray().put(JSONObject().put("startMs", first.getLong("startMs")).put("title", "Practice")))
                .put("ads", JSONArray()).toString(), "fixture")
        }
    }

    private class Fixture(val graph: AppGraph, val provider: Provider) {
        val dao get() = graph.db.library()
        val parts get() = runBlocking { transcriptParts(dao.segments("e", "r")).size }
    }

    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            // Enqueued requests stay unfinished; each test drives the real worker explicitly.
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(context: Context, name: String, params: WorkerParameters): ListenableWorker? =
                    if (name == UnderstandingWorker::class.java.name) HeldAnalysisWorker(context, params) else null
            }).build())
        val inactive = SupervisorJob().apply { cancel() }
        val graph = AppGraph(context, CoroutineScope(inactive + Dispatchers.Main.immediate))
        val provider = Provider()
        ReflectionHelpers.setField(graph, "understanding\$delegate", lazyOf(EpisodeUnderstanding(graph.db, provider, graph.jobs::analyze, graph.jobs::reply)))
        ReflectionHelpers.setField(context, "graph\$delegate", lazyOf(graph))
        assertSame(graph, context.appGraph)
        graph.ready.complete(Unit)
        graph.settings.flag("refresh", false)
        val dao = graph.db.library()
        dao.putPodcast(PodcastEntity("p", "https://example.test/feed", "Show"))
        dao.putEpisode(EpisodeEntity("e", "p", "guid", "Episode", "https://example.test/audio", durationMs = 80000, mediaFingerprint = "fingerprint"))
        dao.putTranscript(TranscriptEntity("e", "r", "fingerprint", "tiny", "en", state = "ready", durationMs = 80000, processedMs = 80000))
        dao.putCoverage(CoverageEntity("e", "r", 0, 80000))
        dao.putSegments((0 until 8).map { SegmentEntity("e", "r", it * 10000L, (it + 1) * 10000L,
            "Consistent practice builds useful skills." + " Supporting detail.".repeat(400)) })
        try { Fixture(graph, provider).also { assertTrue("fixture needs a multi-part analysis", it.parts > 2) }.block() }
        finally {
            manager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
            graph.db.close()
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }

    private val started = mutableListOf<ForegroundInfo>()
    private fun allowed(): ForegroundUpdater = ForegroundUpdater { _, _, info ->
        synchronized(started) { started += info }
        CallbackToFutureAdapter.getFuture<Void> { it.set(null); "allowed" }
    }
    private fun rejected(): ForegroundUpdater = ForegroundUpdater { _, _, _ ->
        CallbackToFutureAdapter.getFuture<Void> { it.setException(android.app.ForegroundServiceStartNotAllowedException("Synthetic background start")); "rejected" }
    }
    private fun worker(id: UUID, foreground: ForegroundUpdater = allowed()) =
        TestListenableWorkerBuilder.from(context, UnderstandingWorker::class.java).setId(id)
            .setInputData(workDataOf("kind" to "analysis", "id" to "e")).setForegroundUpdater(foreground).build()
    private fun unfinished() = manager.getWorkInfosForUniqueWork("analysis:e").get(5, TimeUnit.SECONDS).filter { !it.state.isFinished }

    /** Starts the queued analysis, waits until part [holdAt] is in flight, then stops the worker like the system or the app. */
    private suspend fun Fixture.stopDuringPart(holdAt: Int, reason: Int, beforeStop: suspend (UUID) -> Unit = {}) = coroutineScope {
        val request = unfinished().single().id
        provider.held = CompletableDeferred(); provider.holdAt = provider.calls.get() + holdAt
        val worker = worker(request)
        val run = launch(Dispatchers.IO) { worker.doWork() }
        withTimeout(10000) { provider.held.await() }
        assertEquals("running", dao.analysis("e")!!.state)
        beforeStop(request)
        worker.stop(reason)
        run.cancelAndJoin()
    }

    @Test fun `a system stop keeps the analysis queued and the rescheduled worker resumes from saved parts`() = runBlocking {
        fixture {
            for (reason in listOf(WorkInfo.STOP_REASON_TIMEOUT, WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY, WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT)) {
                manager.cancelUniqueWork("analysis:e").result.get(5, TimeUnit.SECONDS)
                dao.deleteAnalysisParts("e"); dao.putAnalysis(AnalysisEntity("e", "r", state = "failed"))
                graph.understanding.analyze("e")
                stopDuringPart(holdAt = 2, reason = reason)
                val stopped = dao.analysis("e")!!
                assertEquals("stop reason $reason: ${stopped.error}", "queued", stopped.state)
                assertNull(stopped.error)
                assertEquals(1, dao.analysisParts("e", "r").size)
                assertEquals("WorkManager still owns the stopped request", 1, unfinished().size)

                // WorkManager reruns the same request: completed parts are reused, only the interrupted one is re-sent.
                val before = provider.calls.get()
                assertEquals(ListenableWorker.Result.success(), worker(unfinished().single().id).doWork())
                val done = dao.analysis("e")!!
                assertEquals(done.error, "succeeded", done.state)
                assertEquals(parts, done.completedParts)
                assertEquals("one combine request plus every unsaved part", parts - 1 + 1, provider.calls.get() - before)
            }
        }
    }

    @Test fun `pausing in the app or the notification stays paused and is never resumed`() = runBlocking {
        fixture {
            graph.understanding.analyze("e")
            // In-app Pause: cancel the unique work, then the conditional pause (#232).
            stopDuringPart(holdAt = 2, reason = WorkInfo.STOP_REASON_CANCELLED_BY_APP) { graph.jobs.cancelAnalysis("e") }
            graph.understanding.pauseAnalysis("e")
            assertEquals("failed" to "Analysis paused. Completed parts are saved.", dao.analysis("e")!!.let { it.state to it.error })
            assertTrue(unfinished().isEmpty())
            val before = provider.calls.get()
            assertEquals(ListenableWorker.Result.success(), worker(UUID.randomUUID()).doWork())
            assertEquals("failed", dao.analysis("e")!!.state)
            assertEquals(before, provider.calls.get())

            // Notification Pause cancels the request by id; the worker alone records the pause.
            graph.understanding.analyze("e")
            stopDuringPart(holdAt = 2, reason = WorkInfo.STOP_REASON_CANCELLED_BY_APP) { manager.cancelWorkById(it).result.get(5, TimeUnit.SECONDS) }
            assertEquals("failed" to "Analysis paused. Completed parts are saved.", dao.analysis("e")!!.let { it.state to it.error })
            assertEquals(2, dao.analysisParts("e", "r").size)
        }
    }

    @Test fun `a system stop after the user paused stays paused`() = runBlocking {
        fixture {
            graph.understanding.analyze("e")
            stopDuringPart(holdAt = 2, reason = WorkInfo.STOP_REASON_TIMEOUT) { graph.understanding.pauseAnalysis("e") }
            assertEquals("failed" to "Analysis paused. Completed parts are saved.", dao.analysis("e")!!.let { it.state to it.error })
        }
    }

    @Config(sdk = [30])
    @Test fun `without platform stop reasons a cancelled request pauses and a system stop resumes`() = runBlocking {
        fixture {
            graph.understanding.analyze("e")
            stopDuringPart(holdAt = 1, reason = WorkInfo.STOP_REASON_UNKNOWN)
            assertEquals("queued", dao.analysis("e")!!.state)
            stopDuringPart(holdAt = 1, reason = WorkInfo.STOP_REASON_UNKNOWN) { manager.cancelWorkById(it).result.get(5, TimeUnit.SECONDS) }
            assertEquals("failed" to "Analysis paused. Completed parts are saved.", dao.analysis("e")!!.let { it.state to it.error })
        }
    }

    @Test fun `analysis runs as a data sync foreground service with a Pause action`() = runBlocking {
        fixture {
            graph.understanding.analyze("e")
            assertEquals(ListenableWorker.Result.success(), worker(unfinished().single().id).doWork())
            assertEquals("succeeded", dao.analysis("e")!!.state)
            val info = synchronized(started) { started.toList() }.single()
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
            val notification = info.notification
            assertEquals("processing", notification.channelId)
            assertEquals("Generating summary & chapters", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals(listOf("Pause"), notification.actions.map { it.title.toString() })
        }
    }

    @Test fun `a refused foreground start still completes the analysis`() = runBlocking {
        fixture {
            graph.understanding.analyze("e")
            assertEquals(ListenableWorker.Result.success(), worker(unfinished().single().id, rejected()).doWork())
            val row = dao.analysis("e")!!
            assertEquals(row.error, "succeeded", row.state)
        }
    }
}

private class HeldAnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = awaitCancellation()
}
