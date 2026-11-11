package dev.distillpod.android

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.android.ai.AiGateway
import dev.distillpod.android.ai.AiText
import dev.distillpod.android.data.*
import dev.distillpod.android.features.AppViewModel
import dev.distillpod.android.work.UnderstandingWorker
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Controlled investigation only: actual workers and ViewModels, synthetic provider, no live account. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnalysisLifecycleInvestigationTest {
    private val answer = """{"summary":"A synthetic episode explains steady practice.","chapters":[{"startMs":0,"title":"Practice"}],"ads":[]}"""

    /** Delegates to the default dispatcher; records the actual fresh CoroutineWorker Jobs. */
    private class WorkerJobs : CoroutineDispatcher() {
        val jobs: MutableSet<Job> = ConcurrentHashMap.newKeySet()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            context[Job]?.let(jobs::add)
            Dispatchers.Default.dispatch(context, block)
        }
    }

    private class HeldProvider(private val response: String, private val fail: Boolean) : AiGateway {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val cancellations = AtomicInteger()
        val active = AtomicInteger()
        val jobs: MutableSet<Job> = ConcurrentHashMap.newKeySet()
        override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit): AiText {
            jobs += currentCoroutineContext().job
            calls.incrementAndGet()
            active.incrementAndGet()
            entered.complete(Unit)
            try {
                release.await()
                if(fail) throw IOException("Synthetic provider failure")
                return AiText(response, "controlled-fixture")
            } catch(cancelled: CancellationException) {
                cancellations.incrementAndGet()
                throw cancelled
            } finally { active.decrementAndGet() }
        }
    }

    private data class OwnedViewModel(val vm: AppViewModel, val store: ViewModelStore, val job: Job)

    private inner class Fixture(val label: String, val fail: Boolean, val ready: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workerJobs = WorkerJobs()
        val workers = Collections.synchronizedList(mutableListOf<UnderstandingWorker>())
        val provider = HeldProvider(answer, fail)
        val originalGraph: Lazy<AppGraph> = ReflectionHelpers.getField(context, "graph\$delegate")
        lateinit var graph: AppGraph
        val manager get() = WorkManager.getInstance(context)
        val dao get() = graph.db.library()
        val episodeIds = mutableListOf<String>()
        val models = mutableListOf<OwnedViewModel>()
        val observations = mutableListOf<Map<String, Any>>()

        suspend fun start() {
            WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .setWorkerCoroutineContext(workerJobs)
                .setWorkerFactory(object : WorkerFactory() {
                    override fun createWorker(context: Context, name: String, params: WorkerParameters): ListenableWorker {
                        check(name == UnderstandingWorker::class.java.name) { "Unexpected worker class in isolated investigation: $name" }
                        return UnderstandingWorker(context, params).also { workers += it }
                    }
                }).build(), WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS)
            assertSame("Worker coroutine observation must survive test initialization", workerJobs,
                WorkManagerImpl.getInstance(context).configuration.workerCoroutineContext[ContinuationInterceptor])
            val inactive = SupervisorJob().apply { cancel() }
            graph = AppGraph(context, CoroutineScope(inactive + Dispatchers.Main.immediate))
            assertTrue(inactive.isCompleted)
            assertFalse(graph.ready.isCompleted)
            val understanding = EpisodeUnderstanding(graph.db, provider, graph.jobs::analyze, graph.jobs::reply)
            ReflectionHelpers.setField(graph, "understanding\$delegate", lazyOf(understanding))
            ReflectionHelpers.setField(context, "graph\$delegate", lazyOf(graph))
            assertSame(graph, context.appGraph)
            assertSame(understanding, graph.understanding)
            shadowOf(context as Application).declareComponentUnbindable(ComponentName(context, PlaybackService::class.java))
            if(ready) graph.ready.complete(Unit)
        }

        suspend fun seed(suffix: String): String {
            val id = "analysis-investigation-$suffix-${UUID.randomUUID()}"
            episodeIds += id
            dao.putPodcast(PodcastEntity(id, "https://example.test/$id", "Synthetic investigation show"))
            dao.putEpisode(EpisodeEntity(id, id, id, "Synthetic investigation episode", "https://example.test/audio",
                durationMs = 20000, mediaFingerprint = id))
            dao.putTranscript(TranscriptEntity(id, id, id, "publisher", "en", "publisher", "ready", 20000, 20000))
            dao.putCoverage(CoverageEntity(id, id, 0, 20000))
            dao.putSegments(listOf(SegmentEntity(id, id, 0, 20000, "Steady practice improves a useful skill.")))
            return id
        }

        fun model(): OwnedViewModel {
            val vm = AppViewModel(graph, SavedStateHandle())
            return OwnedViewModel(vm, ViewModelStore().apply { put("investigation", vm) },
                vm.viewModelScope.coroutineContext.job).also(models::add)
        }

        fun work(id: String): List<WorkInfo> = manager.getWorkInfosForUniqueWork("analysis:$id").get(5, TimeUnit.SECONDS)

        suspend fun settle(check: suspend () -> Boolean) = withTimeout(15000) {
            while (true) {
                shadowOf(Looper.getMainLooper()).idle()
                for(id in episodeIds) for(info in work(id)) {
                    if(info.state == WorkInfo.State.ENQUEUED) {
                        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
                        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(info.id)
                    }
                }
                if(check()) break
                delay(10)
            }
        }

        suspend fun running(id: String, vm: AppViewModel) = settle {
            provider.entered.isCompleted && provider.active.get() == 1 && dao.analysis(id)?.state == "running" &&
                work(id).any { it.state == WorkInfo.State.RUNNING } && "analysis:$id" !in vm.busy.value
        }

        suspend fun terminal(id: String) = settle {
            val infos = work(id)
            infos.isNotEmpty() && infos.all { it.state.isFinished } && provider.active.get() == 0 &&
                workerJobs.jobs.all { it.isCompleted }
        }

        suspend fun observe(step: String, id: String, extra: Map<String, Any> = emptyMap()) {
            val row = dao.analysis(id)
            observations += mapOf("step" to step, "analysisState" to (row?.state ?: "missing"),
                "completedParts" to (row?.completedParts ?: -1), "summaryPresent" to (row?.summary?.isNotBlank() == true),
                "errorPresent" to (row?.error != null), "workStates" to work(id).map { it.state.name },
                "providerCalls" to provider.calls.get(), "providerCancellations" to provider.cancellations.get(),
                "activeProviderCalls" to provider.active.get(), "workerInstances" to workers.size,
                "workerStopReasons" to workers.map { it.stopReason }) + extra
        }

        suspend fun close() {
            // Release before cancellation/teardown, then prove all real worker and VM work has unwound.
            provider.release.complete(Unit)
            for(model in models) model.store.clear()
            for(id in episodeIds) manager.cancelUniqueWork("analysis:$id").result.get(5, TimeUnit.SECONDS)
            settle {
                provider.active.get() == 0 && provider.jobs.all { it.isCompleted } &&
                    workerJobs.jobs.all { it.isCompleted } && !WorkManagerImpl.getInstance(context).processor.hasWork() &&
                    episodeIds.all { id -> work(id).all { it.state.isFinished } }
            }
            if(workers.isNotEmpty()) assertTrue("Worker coroutine completion check must not be vacuous", workerJobs.jobs.isNotEmpty())
            for(model in models) model.job.awaitViewModelTeardown()
            // Only after coroutine/collector completion may either database close.
            graph.db.close()
            WorkManagerTestInitHelper.closeWorkDatabase()
        }

        fun save(outcome: String) {
            System.getenv("DISTILLPOD_ANALYSIS_INVESTIGATION_DIR")?.takeIf { it.isNotBlank() }?.let { path ->
                val directory = File(path).also { it.mkdirs() }
                File(directory, "$label.json").writeText(JSONObject(mapOf("scenario" to label, "harnessOutcome" to outcome,
                    "fakeProvider" to true, "actualWorker" to UnderstandingWorker::class.java.name,
                    "workerCoroutineJobs" to workerJobs.jobs.size, "workerJobsCompleted" to workerJobs.jobs.all { it.isCompleted },
                    "providerJobsCompleted" to provider.jobs.all { it.isCompleted },
                    "viewModelJobsCompleted" to models.all { it.job.isCompleted }, "observations" to observations)).toString(2))
            }
        }
    }

    private suspend fun fixture(label: String, fail: Boolean = false, ready: Boolean = true, body: suspend Fixture.() -> Unit) {
        val fixture = Fixture(label, fail, ready)
        var failure: Throwable? = null
        try { fixture.start(); fixture.body() }
        catch(problem: Throwable) { failure = problem }
        finally {
            try { fixture.close() } catch(problem: Throwable) {
                if(failure == null) failure = problem else failure!!.addSuppressed(problem)
            }
            try { ReflectionHelpers.setField(fixture.context, "graph\$delegate", fixture.originalGraph) }
            catch(problem: Throwable) { if(failure == null) failure = problem else failure!!.addSuppressed(problem) }
            try { fixture.save(if(failure == null) "passed" else "failed") }
            catch(problem: Throwable) { if(failure == null) failure = problem else failure!!.addSuppressed(problem) }
        }
        failure?.let { throw it }
    }

    @Test fun `accepted actual analysis survives navigation duplicate requests and ViewModel destruction`() = runBlocking {
        fixture("navigation-and-viewmodel") {
            val id = seed("navigation")
            val original = model()
            original.vm.open("analysis:$id")
            original.vm.analyze(id)
            running(id, original.vm)
            val attempt = dao.analysis(id)!!.attempt
            observe("running-before-navigation", id)
            original.vm.open("library")
            original.vm.back()
            assertEquals("analysis:$id", original.vm.backStack.value.last())
            original.vm.back()
            assertEquals("library", original.vm.backStack.value.last())
            repeat(2) { original.vm.analyze(id); settle { "analysis:$id" !in original.vm.busy.value } }
            assertEquals(attempt, dao.analysis(id)!!.attempt)
            assertEquals(1, provider.calls.get())
            assertEquals(0, provider.cancellations.get())
            original.store.clear()
            original.job.awaitViewModelTeardown()
            assertTrue(original.job.isCompleted)
            assertEquals("running", dao.analysis(id)!!.state)
            assertTrue(work(id).any { it.state == WorkInfo.State.RUNNING })
            assertTrue(provider.jobs.any { it.isActive })
            observe("viewmodel-destroyed-worker-still-running", id, mapOf("originalViewModelCompleted" to true))
            provider.release.complete(Unit)
            terminal(id)
            val result = dao.analysis(id)!!
            assertEquals("succeeded", result.state)
            assertEquals(attempt, result.attempt)
            assertEquals("A synthetic episode explains steady practice.", result.summary)
            assertEquals("Practice", JSONArray(result.chaptersJson).getJSONObject(0).getString("title"))
            assertEquals(1, result.completedParts)
            assertEquals(1, provider.calls.get())
            assertTrue(work(id).all { it.state == WorkInfo.State.SUCCEEDED })
            val replacement = model()
            replacement.vm.open("analysis:$id")
            settle { replacement.vm.analyses.value.any { it.episodeId == id && it.state == "succeeded" } }
            assertEquals(result.summary, replacement.vm.analyses.value.single { it.episodeId == id }.summary)
            observe("replacement-viewmodel-observes-persisted-success", id)
        }
    }

    @Test fun `explicit pause cancels the real worker and preserves a manual retry state`() = runBlocking {
        fixture("explicit-pause") {
            val id = seed("pause")
            val original = model()
            original.vm.analyze(id)
            running(id, original.vm)
            original.vm.pauseAnalysis(id)
            settle { "analysis:$id" !in original.vm.busy.value && provider.cancellations.get() == 1 && dao.analysis(id)?.state == "failed" }
            terminal(id)
            provider.release.complete(Unit)
            assertTrue(work(id).all { it.state == WorkInfo.State.CANCELLED })
            assertEquals(1, provider.calls.get())
            assertEquals(1, provider.cancellations.get())
            assertEquals("failed", dao.analysis(id)!!.state)
            assertTrue(dao.analysis(id)!!.summary.isEmpty())
            assertEquals(0, dao.analysis(id)!!.completedParts)
            assertNotNull(dao.analysis(id)!!.error)
            assertEquals("ready", dao.transcript(id)!!.state)
            observe("explicit-pause-cancelled-provider-and-worker", id)
        }
    }

    @Test fun `actual worker cancellation before provider entry unwinds an observed worker coroutine`() = runBlocking {
        fixture("cancel-before-provider", ready = false) {
            val id = seed("before-provider")
            val original = model()
            original.vm.analyze(id)
            settle { work(id).any { it.state == WorkInfo.State.RUNNING } && workerJobs.jobs.any { it.isActive } &&
                "analysis:$id" !in original.vm.busy.value }
            assertFalse(graph.ready.isCompleted)
            assertFalse(provider.entered.isCompleted)
            assertEquals(0, provider.calls.get())
            assertTrue(provider.jobs.isEmpty())
            assertEquals("queued", dao.analysis(id)!!.state)
            original.vm.pauseAnalysis(id)
            settle { "analysis:$id" !in original.vm.busy.value && dao.analysis(id)?.state == "failed" }
            terminal(id)
            assertTrue(workerJobs.jobs.isNotEmpty())
            assertTrue(workerJobs.jobs.all { it.isCompleted })
            assertTrue(work(id).all { it.state == WorkInfo.State.CANCELLED })
            assertEquals("failed", dao.analysis(id)!!.state)
            assertEquals(0, provider.calls.get())
            observe("worker-cancelled-before-provider-entry", id, mapOf("providerNeverEntered" to true))
        }
    }

    @Test fun `handled provider failure can finish WorkManager work successfully while analysis fails`() = runBlocking {
        fixture("provider-failure", fail = true) {
            val id = seed("failure")
            val original = model()
            original.vm.analyze(id)
            running(id, original.vm)
            provider.release.complete(Unit)
            terminal(id)
            assertTrue(work(id).all { it.state == WorkInfo.State.SUCCEEDED })
            assertEquals("failed", dao.analysis(id)!!.state)
            assertTrue(dao.analysis(id)!!.summary.isEmpty())
            assertNotNull(dao.analysis(id)!!.error)
            assertEquals(1, provider.calls.get())
            assertEquals(0, provider.cancellations.get())
            observe("worker-success-is-not-analysis-success", id)
        }
    }

    @Test fun `persisted recovery requires manual retry for running analysis and schedules queued analysis`() = runBlocking {
        fixture("persisted-recovery") {
            val runningId = seed("interrupted")
            val queuedId = seed("queued")
            val interrupted = AnalysisEntity(runningId, runningId, state = "running", completedParts = 1,
                totalParts = 2, attempt = UUID.randomUUID().toString())
            dao.putAnalysis(interrupted)
            dao.putAnalysisPart(AnalysisPartEntity(runningId, runningId, 0, JSONObject(answer).put("model", "controlled-fixture").toString()))
            dao.putAnalysis(AnalysisEntity(queuedId, queuedId, attempt = UUID.randomUUID().toString()))
            graph.understanding.recoverInterrupted()
            settle { provider.entered.isCompleted && dao.analysis(queuedId)?.state == "running" }
            assertEquals("failed", dao.analysis(runningId)!!.state)
            assertTrue(dao.analysis(runningId)!!.error.orEmpty().contains("Retry"))
            assertEquals(1, dao.analysisParts(runningId, runningId).size)
            assertTrue(work(runningId).isEmpty())
            assertEquals(1, provider.calls.get())
            observe("seeded-interrupted-analysis-retains-checkpoint-no-work-enqueued", runningId)
            provider.release.complete(Unit)
            terminal(queuedId)
            assertEquals("succeeded", dao.analysis(queuedId)!!.state)
            assertEquals("failed", dao.analysis(runningId)!!.state)
            assertEquals(1, provider.calls.get())
            observe("seeded-queued-analysis-scheduled-and-completed", queuedId)
        }
    }
}
