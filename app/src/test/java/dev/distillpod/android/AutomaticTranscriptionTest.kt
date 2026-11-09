package dev.distillpod.android

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomaticTranscriptionTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val episode = EpisodeEntity("auto-episode", "auto-podcast", "guid", "Episode", "https://example.test/audio.mp3",
        mediaFingerprint = "current-audio", durationMs = 10000)

    private suspend fun isolatedGraph(ctx: Context = context): AppGraph {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        // This fixture tests explicit repository calls, not app startup or worker recovery.
        // Cancel before construction so DEFAULT-start graph jobs never begin, even on IO.
        val isolatedJob = SupervisorJob().apply { cancel() }
        val graph = AppGraph(ctx, CoroutineScope(isolatedJob + Dispatchers.Main.immediate))
        assertTrue(graph.scope.coroutineContext[Job]!!.isCompleted)
        assertFalse(graph.scope.isActive)
        assertFalse(graph.scope.coroutineContext[Job]!!.children.any())
        assertFalse(graph.ready.isCompleted)
        graph.settings.flag("transcribeOnPlayback", true)
        graph.settings.flag("onlineTranscripts", false)
        graph.db.library().putPodcast(PodcastEntity(episode.podcastId, "https://example.test/feed", "Show"))
        graph.db.library().putEpisode(episode)
        return graph
    }

    @Test fun `setting defaults on and disabled value survives reopening storage`() = runTest {
        val folder = Files.createTempDirectory("automatic-transcription-").toFile()
        val file = File(folder, "test.preferences_pb")
        try {
            settingsSession(context, file) {
                assertTrue(it.flow.first().transcribeOnPlayback)
                it.flag("transcribeOnPlayback", false)
            }
            settingsSession(context, file) { assertFalse(it.flow.first().transcribeOnPlayback) }
        } finally { folder.deleteRecursively() }
    }

    @Test fun `disabled setting and diagnostic clips never create work but manual preparation still works`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            graph.settings.flag("transcribeOnPlayback", false)
            graph.transcription.prepareAutomatically(episode.id)
            assertNull(graph.db.library().transcript(episode.id))
            graph.settings.flag("transcribeOnPlayback", true)
            graph.transcription.prepareAutomatically("diagnostic-clip")
            assertTrue(graph.db.library().transcripts().isEmpty())
            graph.settings.flag("transcribeOnPlayback", false)
            graph.transcription.prepare(episode.id)
            assertEquals("queued", graph.db.library().transcript(episode.id)!!.state)
        } finally { graph.db.close() }
    }

    @Test fun `automatic requests reuse existing states despite a model or language change`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            for (state in listOf("ready", "queued", "processing", "waiting_model", "paused", "failed", "partial")) {
                val old = TranscriptEntity(episode.id, "saved-$state", episode.mediaFingerprint, "base", "pt",
                    state = state, processedMs = 5000, error = "Keep this state")
                dao.putTranscript(old)
                dao.putSegments(listOf(SegmentEntity(episode.id, old.revision, 0, 5000, "Saved text", 0)))
                graph.transcription.prepareAutomatically(episode.id)
                assertEquals(state, old, dao.transcript(episode.id))
                assertEquals("Saved text", dao.window(episode.id, old.revision, 0, 5000).single().text)
            }
        } finally { graph.db.close() }
    }

    @Test fun `changed audio and explicit stale state get new revisions while pause remains respected`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            for (old in listOf(
                TranscriptEntity(episode.id, "old-audio", "obsolete-audio", "tiny", "auto", state = "ready"),
                TranscriptEntity(episode.id, "stale-text", episode.mediaFingerprint, "tiny", "auto", state = "stale"),
            )) {
                dao.putTranscript(old)
                dao.putSegments(listOf(SegmentEntity(episode.id, old.revision, 0, 5000, "Old text", 0)))
                graph.transcription.prepareAutomatically(episode.id)
                val current = dao.transcript(episode.id)!!
                assertNotEquals(old.revision, current.revision)
                assertEquals("queued", current.state)
                assertEquals(episode.mediaFingerprint, current.fingerprint)
                assertTrue(dao.window(episode.id, old.revision, 0, 5000).isEmpty())
            }
            val paused = dao.transcript(episode.id)!!.copy(state = "paused", fingerprint = "previous-audio")
            dao.putTranscript(paused)
            graph.transcription.prepareAutomatically(episode.id)
            assertEquals(paused, dao.transcript(episode.id))
        } finally { graph.db.close() }
    }

    @Test fun `concurrent manual and playback requests share a revision and queued work`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            coroutineScope {
                repeat(20) { launch(Dispatchers.IO) { graph.transcription.prepareAutomatically(episode.id) } }
                launch(Dispatchers.IO) { graph.transcription.prepare(episode.id) }
            }
            val current = graph.db.library().transcript(episode.id)!!
            repeat(5) { graph.transcription.prepareAutomatically(episode.id) }
            assertEquals(current, graph.db.library().transcript(episode.id))
            graph.settings.flag("transcribeOnPlayback", false)
            graph.transcription.prepareAutomatically(episode.id)
            assertEquals(current, graph.db.library().transcript(episode.id))
        } finally { graph.db.close() }
    }

    @Test fun `missing model waits and model availability resumes without restarting the revision`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            graph.transcription.prepareAutomatically(episode.id)
            val row = graph.db.library().transcript(episode.id)!!
            graph.transcription.process(episode.id, row.revision, TranscriptExecution({ false }, {}, false))
            assertEquals("waiting_model", graph.db.library().transcript(episode.id)!!.state)
            graph.transcription.prepareAutomatically(episode.id)
            assertEquals("waiting_model", graph.db.library().transcript(episode.id)!!.state)
            graph.transcription.resumeModel(row.model)
            assertEquals("queued", graph.db.library().transcript(episode.id)!!.state)
            assertEquals(row.revision, graph.db.library().transcript(episode.id)!!.revision)
        } finally { graph.db.close() }
    }

    @Test fun `missing model cannot overwrite a pause from an in flight pass`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            val row = TranscriptEntity(episode.id, "pause-before-model", episode.mediaFingerprint, "missing-test-model", "auto")
            dao.putTranscript(row)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            // Gate the pass before model lookup; pause must cancel it and clear progress
            // without relying on sleeps or scheduler timing.
            val worker = launch {
                graph.transcription.process(episode.id, row.revision, TranscriptExecution({ false }, {
                    entered.complete(Unit)
                    release.await()
                }, false))
            }
            try {
                withTimeout(5000) { entered.await() }
                graph.transcription.pause(episode.id)
            } finally { release.complete(Unit) }
            withTimeout(5000) { worker.join() }
            assertEquals("paused", dao.transcript(episode.id)!!.state)
            assertNull(dao.transcript(episode.id)!!.error)
            assertFalse(graph.transcription.progress.value.containsKey(episode.id))
        } finally { graph.db.close() }
    }

    @Test fun `missing model cannot restore superseded or terminal work`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            val row = TranscriptEntity(episode.id, "obsolete-model-check", episode.mediaFingerprint, "missing-test-model", "auto")
            for (replacement in listOf(
                // Persist the control result independently of this worker's cancellation,
                // as when an already-dispatched database write races with user controls.
                row.copy(state = "paused"),
                row.copy(revision = "replacement", state = "queued"),
                row.copy(state = "stale"),
                row.copy(state = "ready"),
                row.copy(state = "partial"),
            )) {
                dao.putTranscript(row)
                graph.transcription.process(episode.id, row.revision, TranscriptExecution({ false }, {
                    dao.putTranscript(replacement)
                }, false))
                assertEquals(replacement, dao.transcript(episode.id))
            }
        } finally { graph.db.close() }
    }

    @Test fun `missing model preserves current progress when publisher fallback leaves work queued`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            val row = TranscriptEntity(episode.id, "queued-fallback", episode.mediaFingerprint, "missing-test-model", "auto")
            val current = row.copy(processedMs = 5000, durationMs = 10000)
            dao.putTranscript(row)
            graph.transcription.process(episode.id, row.revision, TranscriptExecution({ false }, {
                dao.putTranscript(current)
            }, false))
            assertEquals(current.copy(state = "waiting_model",
                error = "Download the selected transcription model in Settings."), dao.transcript(episode.id))
        } finally { graph.db.close() }
    }

    @Test fun `an active pipeline keeps its revision while the downloaded audio identity changes`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            val row = TranscriptEntity(episode.id, "active-download", episode.mediaFingerprint, "tiny", "auto", lookupOnline = true)
            dao.putTranscript(row)
            lateinit var repository: TranscriptionRepository
            repository = TranscriptionRepository(graph, onlineLookup = { _, _ ->
                // Reproduce the window between downloadComplete and the chunk adopting the file hash.
                dao.putEpisode(episode.copy(mediaFingerprint = "downloaded-file-hash"))
                val transitional = row.copy(state = "stale")
                dao.putTranscript(transitional)
                repository.prepareAutomatically(episode.id)
                assertEquals(transitional, dao.transcript(episode.id))
                currentCoroutineContext().ensureActive()
                true
            })
            repository.process(episode.id, row.revision, TranscriptExecution({ false }, {}, false))
        } finally { graph.db.close() }
    }

    @Test fun `worker foreground rejection preserves checkpoints and stays retryable`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            val row = TranscriptEntity(episode.id, "startup-rejection", episode.mediaFingerprint, "tiny", "auto",
                processedMs = 5000, durationMs = 10000)
            dao.putTranscript(row)
            val segment = SegmentEntity(episode.id, row.revision, 0, 5000, "Saved text", 0)
            val coverage = CoverageEntity(episode.id, row.revision, 0, 5000)
            dao.putSegments(listOf(segment)); dao.putCoverage(coverage)
            val outcome = graph.transcription.process(episode.id, row.revision, TranscriptExecution({ false }, {
                throw android.app.ForegroundServiceStartNotAllowedException("Synthetic startup rejection")
            }, true))
            assertEquals(TranscriptOutcome.DEFERRED_START, outcome)
            assertEquals(androidx.work.ListenableWorker.Result.retry(), outcome.workerResult())
            assertEquals("queued", dao.transcript(episode.id)!!.state)
            assertEquals(row.revision, dao.transcript(episode.id)!!.revision)
            assertEquals(5000L, dao.transcript(episode.id)!!.processedMs)
            assertEquals(listOf(segment), dao.segments(episode.id, row.revision))
            assertEquals(listOf(coverage), dao.coverage(episode.id, row.revision))
        } finally { graph.db.close() }
    }

    @Test fun `repeated wrapped rejection exhausts budget and explicit resume keeps the revision`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            val row = TranscriptEntity(episode.id, "bounded-start", episode.mediaFingerprint, "tiny", "auto")
            dao.putTranscript(row)
            for (attempt in 0..4) {
                val outcome = graph.transcription.process(episode.id, row.revision, TranscriptExecution({ false }, {
                    throw java.util.concurrent.ExecutionException(android.app.ForegroundServiceStartNotAllowedException("Synthetic"))
                }, true, attempt))
                assertEquals(if (attempt < 4) TranscriptOutcome.DEFERRED_START else TranscriptOutcome.RESUME_REQUIRED, outcome)
                assertEquals(if (attempt < 4) "queued" else "paused", dao.transcript(episode.id)!!.state)
            }
            assertTrue(dao.transcript(episode.id)!!.error!!.contains("Resume"))
            graph.transcription.prepareAutomatically(episode.id)
            assertEquals("paused", dao.transcript(episode.id)!!.state)
            graph.transcription.recover()
            assertEquals("paused", dao.transcript(episode.id)!!.state)
            graph.transcription.prepare(episode.id)
            assertEquals(row.revision, dao.transcript(episode.id)!!.revision)
            assertEquals("queued", dao.transcript(episode.id)!!.state)
            assertNull(dao.transcript(episode.id)!!.error)
            assertEquals(TranscriptOutcome.DEFERRED_START, graph.transcription.process(episode.id, row.revision,
                TranscriptExecution({ false }, { throw android.app.ForegroundServiceStartNotAllowedException("Synthetic") }, true, 0)))
        } finally { graph.db.close() }
    }

    @Test fun `startup rejection cannot overwrite a newer control result`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            val row = TranscriptEntity(episode.id, "guarded-start", episode.mediaFingerprint, "tiny", "auto")
            for (replacement in listOf(row.copy(state = "paused"), row.copy(state = "ready"),
                row.copy(state = "partial"), row.copy(state = "stale"), row.copy(revision = "new"))) {
                dao.putTranscript(row)
                val outcome = graph.transcription.process(episode.id, row.revision, TranscriptExecution({ false }, {
                    dao.putTranscript(replacement)
                    throw android.app.ForegroundServiceStartNotAllowedException("Synthetic")
                }, true))
                assertEquals(replacement, dao.transcript(episode.id))
                assertEquals(TranscriptOutcome.COMPLETED, outcome)
            }
        } finally { graph.db.close() }
    }

    @Test fun `permanent foreground errors fail and cancellation propagates`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            val row = TranscriptEntity(episode.id, "permanent-start", episode.mediaFingerprint, "tiny", "auto")
            for (failure in listOf(IllegalStateException("Not an Android startup restriction"), SecurityException("Missing permission"))) {
                dao.putTranscript(row)
                val outcome = graph.transcription.process(episode.id, row.revision,
                    TranscriptExecution({ false }, { throw failure }, true))
                assertEquals(TranscriptOutcome.FAILED, outcome)
                assertEquals(androidx.work.ListenableWorker.Result.failure(), outcome.workerResult())
                assertEquals("failed", dao.transcript(episode.id)!!.state)
            }
            dao.putTranscript(row)
            try {
                graph.transcription.process(episode.id, row.revision,
                    TranscriptExecution({ false }, { throw CancellationException("Stopped") }, true))
                fail("Cancellation must propagate")
            } catch (_: CancellationException) {}
            assertEquals("queued", dao.transcript(episode.id)!!.state)
        } finally { graph.db.close() }
    }

    @Test fun `deferred startup can recover and complete without replacing checkpoints`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            val row = TranscriptEntity(episode.id, "recovered-start", episode.mediaFingerprint, "tiny", "auto", lookupOnline = true)
            dao.putTranscript(row)
            val repository = TranscriptionRepository(graph, onlineLookup = { _, current ->
                dao.putTranscript(current.copy(state = "ready"))
                true
            })
            assertEquals(TranscriptOutcome.DEFERRED_START, repository.process(episode.id, row.revision,
                TranscriptExecution({ false }, { throw android.app.ForegroundServiceStartNotAllowedException("Synthetic") }, true)))
            val outcome = repository.process(episode.id, row.revision, TranscriptExecution({ false }, {}, true, 1))
            assertEquals(TranscriptOutcome.COMPLETED, outcome)
            assertEquals(androidx.work.ListenableWorker.Result.success(), outcome.workerResult())
            assertEquals("ready", dao.transcript(episode.id)!!.state)
            assertEquals(row.revision, dao.transcript(episode.id)!!.revision)
        } finally { graph.db.close() }
    }

    @Test fun `actual worker retries rejected foreground future and fails after its budget`() = runBlocking<Unit> {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val graph = context.appGraph
        withTimeout(10000) {
            while (!graph.ready.isCompleted) { shadowOf(Looper.getMainLooper()).idle(); delay(10) }
        }
        graph.ready.await()
        graph.scope.cancel()
        withTimeout(5000) {
            while (!graph.scope.coroutineContext[Job]!!.isCompleted) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
        val dao = graph.db.library()
        try {
            dao.putPodcast(PodcastEntity(episode.podcastId, "https://example.test/feed", "Show"))
            dao.putEpisode(episode)
            val row = TranscriptEntity(episode.id, "real-worker-start", episode.mediaFingerprint, "tiny", "auto", processedMs = 5000)
            dao.putTranscript(row)
            dao.putCoverage(CoverageEntity(episode.id, row.revision, 0, 5000))
            fun worker(attempt: Int) = androidx.work.testing.TestListenableWorkerBuilder.from(context, TranscriptWorker::class.java)
                .setInputData(androidx.work.workDataOf("episode" to episode.id, "revision" to row.revision))
                .setRunAttemptCount(attempt)
                .setForegroundUpdater { _, _, _ ->
                    androidx.concurrent.futures.CallbackToFutureAdapter.getFuture<Void> { completer ->
                        completer.setException(android.app.ForegroundServiceStartNotAllowedException("Synthetic platform rejection"))
                        "foreground rejection"
                    }
                }.build()
            assertEquals(androidx.work.ListenableWorker.Result.retry(), worker(0).doWork())
            assertEquals("queued", dao.transcript(episode.id)!!.state)
            assertEquals(androidx.work.ListenableWorker.Result.failure(), worker(4).doWork())
            assertEquals("paused", dao.transcript(episode.id)!!.state)
            assertEquals(5000L, dao.transcript(episode.id)!!.processedMs)
            assertEquals(5000L, dao.coverage(episode.id, row.revision).single().endMs)
            graph.transcription.prepare(episode.id)
            assertEquals(row.revision, dao.transcript(episode.id)!!.revision)
            assertEquals(androidx.work.ListenableWorker.Result.retry(), worker(0).doWork())
            graph.transcription.pause(episode.id)
            assertEquals(androidx.work.ListenableWorker.Result.success(), worker(1).doWork())
            assertEquals("paused", dao.transcript(episode.id)!!.state)
        } finally { graph.db.close() }
    }

    @Test fun `explicit resume replaces exhausting running request without cancelling another revision`() = runBlocking<Unit> {
        val rejected = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? =
                throw android.app.ForegroundServiceStartNotAllowedException("Synthetic startup rejection")
        }
        val graph = isolatedGraph(rejected)
        try {
            graph.settings.flag("charging", true)
            graph.transcription.prepareAutomatically(episode.id)
            val dao = graph.db.library()
            val row = dao.transcript(episode.id)!!
            val manager = WorkManager.getInstance(context) as androidx.work.impl.WorkManagerImpl
            val name = "transcript:${episode.id}:${row.revision}:0"
            val old = manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).single()
            val specs = manager.workDatabase.workSpecDao()
            // The real worker has persisted exhaustion but has not delivered its Result.failure yet.
            specs.setState(androidx.work.WorkInfo.State.RUNNING, old.id.toString())
            repeat(4) { specs.incrementWorkSpecRunAttemptCount(old.id.toString()) }
            dao.putTranscript(row.copy(state = "paused", error = "Android could not start transcription. Tap Resume."))
            dao.putCoverage(CoverageEntity(episode.id, row.revision, 0, 5000))
            val unrelated = androidx.work.OneTimeWorkRequestBuilder<TranscriptWorker>()
                .setConstraints(androidx.work.Constraints.Builder().setRequiresCharging(true).build())
                .addTag("transcript:${episode.id}")
                .addTag("transcript-revision:${episode.id}:other").build()
            manager.enqueue(unrelated).result.get(5, TimeUnit.SECONDS)

            graph.transcription.prepare(episode.id)
            val fresh = manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).single { !it.state.isFinished }
            assertNotEquals("Resume must not retain the exhausted RUNNING request", old.id, fresh.id)
            assertEquals(0, fresh.runAttemptCount)
            assertTrue(fresh.constraints.requiresCharging())
            assertTrue(fresh.constraints.requiresBatteryNotLow())
            assertEquals(androidx.work.NetworkType.UNMETERED, fresh.constraints.requiredNetworkType)
            assertEquals(androidx.work.BackoffPolicy.EXPONENTIAL, specs.getWorkSpec(fresh.id.toString())!!.backoffPolicy)
            assertEquals(30000L, specs.getWorkSpec(fresh.id.toString())!!.backoffDelayDuration)
            // KEEP can prune the cancelled row while replacing the same unique name.
            val retired = manager.getWorkInfoById(old.id).get()
            assertTrue(retired == null || retired.state == androidx.work.WorkInfo.State.CANCELLED)
            assertFalse(manager.getWorkInfoById(unrelated.id).get().state.isFinished)
            assertEquals(row.revision, dao.transcript(episode.id)!!.revision)
            assertEquals("queued", dao.transcript(episode.id)!!.state)
            assertEquals(5000L, dao.coverage(episode.id, row.revision).single().endMs)
        } finally { graph.db.close() }
    }

    @Test fun `cancelled foreground rejection cannot repause explicitly resumed revision`() = runBlocking<Unit> {
        val rejected = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? =
                throw android.app.ForegroundServiceStartNotAllowedException("Synthetic")
        }
        val graph = isolatedGraph(rejected)
        try {
            val dao = graph.db.library()
            val row = TranscriptEntity(episode.id, "cancelled-start", episode.mediaFingerprint, "tiny", "auto")
            dao.putTranscript(row)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val old = launch {
                graph.transcription.process(episode.id, row.revision, TranscriptExecution({ false }, {
                    withContext(NonCancellable) { entered.complete(Unit); release.await() }
                    throw android.app.ForegroundServiceStartNotAllowedException("Late failure")
                }, true, 4))
            }
            try {
                withTimeout(5000) { entered.await() }
                graph.transcription.pause(episode.id)
                graph.transcription.prepare(episode.id)
            } finally { release.complete(Unit) }
            withTimeout(5000) { old.join() }
            assertTrue(old.isCancelled)
            assertEquals(row.revision, dao.transcript(episode.id)!!.revision)
            assertEquals("queued", dao.transcript(episode.id)!!.state)
            assertNull(dao.transcript(episode.id)!!.error)
        } finally { graph.db.close() }
    }

    @Test fun `cancellation while recovery waits for controls cannot exhaust resumed work`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            val row = TranscriptEntity(episode.id, "locked-recovery", episode.mediaFingerprint, "tiny", "auto")
            dao.putTranscript(row)
            val repository = graph.transcription
            // Hold the real control mutex to stage cancellation after classification but before mutation.
            val field = TranscriptionRepository::class.java.getDeclaredField("controls").apply { isAccessible = true }
            val controls = field.get(repository) as kotlinx.coroutines.sync.Mutex
            val entered = CompletableDeferred<Unit>()
            val reject = CompletableDeferred<Unit>()
            val old = launch {
                repository.process(episode.id, row.revision, TranscriptExecution({ false }, {
                    entered.complete(Unit)
                    reject.await()
                    throw android.app.ForegroundServiceStartNotAllowedException("Synthetic")
                }, true, 4))
            }
            withTimeout(5000) { entered.await() }
            controls.lock()
            try {
                reject.complete(Unit)
                // Same runBlocking event loop: child runs synchronously through classification
                // until it suspends on the held control mutex in NonCancellable cleanup.
                yield()
                assertTrue(old.isActive)
                old.cancel()
                dao.putTranscript(row.copy(state = "paused"))
                dao.putTranscript(row.copy(state = "queued"))
            } finally { controls.unlock() }
            withTimeout(5000) { old.join() }
            assertEquals("queued", dao.transcript(episode.id)!!.state)
            assertNull(dao.transcript(episode.id)!!.error)
        } finally { graph.db.close() }
    }

    @Test fun `foreground rejection retains one constrained recovery job`() = runBlocking<Unit> {
        val rejected = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? = throw IllegalStateException("Background start rejected")
        }
        val graph = isolatedGraph(rejected)
        try {
            repeat(3) { graph.transcription.prepareAutomatically(episode.id) }
            val row = graph.db.library().transcript(episode.id)!!
            assertEquals("queued", row.state)
            val jobs = WorkManager.getInstance(context).getWorkInfosForUniqueWork("transcript:${episode.id}:${row.revision}:0").get(5, TimeUnit.SECONDS)
            assertEquals(1, jobs.size)
            assertFalse(jobs.single().state.isFinished)
            assertTrue(jobs.single().constraints.requiresBatteryNotLow())
            assertEquals(androidx.work.NetworkType.UNMETERED, jobs.single().constraints.requiredNetworkType)
            graph.transcription.recover()
            val recovered = WorkManager.getInstance(context).getWorkInfosForUniqueWork("transcript:${episode.id}:${row.revision}:0").get(5, TimeUnit.SECONDS)
            assertEquals(jobs.single().id, recovered.single().id)
        } finally { graph.db.close() }
    }
}
