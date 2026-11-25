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
                    state = state, processedMs = 5000, error = "Keep this state", localCreatedAt = 1000)
                dao.putTranscript(old, createdAt = 1000)
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
            val row = TranscriptEntity(episode.id, "obsolete-model-check", episode.mediaFingerprint, "missing-test-model", "auto", localCreatedAt = 1000)
            for (replacement in listOf(
                // Persist the control result independently of this worker's cancellation,
                // as when an already-dispatched database write races with user controls.
                row.copy(state = "paused"),
                row.copy(revision = "replacement", state = "queued"),
                row.copy(state = "stale"),
                row.copy(state = "ready"),
                row.copy(state = "partial"),
            )) {
                dao.putTranscript(row, createdAt = 1000)
                graph.transcription.process(episode.id, row.revision, TranscriptExecution({ false }, {
                    dao.putTranscript(replacement, createdAt = 1000)
                }, false))
                assertEquals(replacement, dao.transcript(episode.id))
            }
        } finally { graph.db.close() }
    }

    @Test fun `missing model preserves current progress when publisher fallback leaves work queued`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            val row = TranscriptEntity(episode.id, "queued-fallback", episode.mediaFingerprint, "missing-test-model", "auto", localCreatedAt = 1000)
            val current = row.copy(processedMs = 5000, durationMs = 10000)
            dao.putTranscript(row, createdAt = 1000)
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
            val row = TranscriptEntity(episode.id, "active-download", episode.mediaFingerprint, "tiny", "auto", lookupOnline = true, localCreatedAt = 1000)
            dao.putTranscript(row, createdAt = 1000)
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

    /** The chunk's own work: an online lookup that completes the revision, standing in for inference. */
    private fun completingRepository(graph: AppGraph, calls: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger()) =
        TranscriptionRepository(graph, onlineLookup = { _, current ->
            calls.incrementAndGet()
            graph.db.library().putTranscript(current.copy(state = "ready"))
            true
        })

    @Test fun `a refused foreground start continues the chunk as ordinary work and keeps checkpoints`() = runBlocking<Unit> {
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
            // The pass went on past the refused start: it reached the model check (no model is installed here).
            assertEquals(TranscriptOutcome.COMPLETED, outcome)
            assertEquals(androidx.work.ListenableWorker.Result.success(), outcome.workerResult())
            assertEquals("waiting_model", dao.transcript(episode.id)!!.state)
            assertEquals(row.revision, dao.transcript(episode.id)!!.revision)
            assertEquals(5000L, dao.transcript(episode.id)!!.processedMs)
            assertEquals(listOf(segment), dao.segments(episode.id, row.revision))
            assertEquals(listOf(coverage), dao.coverage(episode.id, row.revision))
        } finally { graph.db.close() }
    }

    @Test fun `repeated wrapped rejections never pause and each run completes its work`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val repository = completingRepository(graph, calls)
            val row = TranscriptEntity(episode.id, "bounded-start", episode.mediaFingerprint, "tiny", "auto", lookupOnline = true)
            // Earlier versions paused on the fifth refused attempt; attempts beyond it make no difference now.
            for (attempt in 0..6) {
                dao.putTranscript(row)
                val outcome = repository.process(episode.id, row.revision, TranscriptExecution({ false }, {
                    throw java.util.concurrent.ExecutionException(android.app.ForegroundServiceStartNotAllowedException("Synthetic"))
                }, true))
                assertEquals(TranscriptOutcome.COMPLETED, outcome)
                assertEquals("ready", dao.transcript(episode.id)!!.state)
                assertNull(dao.transcript(episode.id)!!.error)
                assertEquals(row.revision, dao.transcript(episode.id)!!.revision)
                assertEquals(attempt + 1, calls.get())
            }
        } finally { graph.db.close() }
    }

    @Test fun `a stop after a refused start keeps the revision queued and the next run resumes it`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            val row = TranscriptEntity(episode.id, "stopped-after-refusal", episode.mediaFingerprint, "tiny", "auto",
                processedMs = 5000, durationMs = 10000, lookupOnline = true)
            dao.putTranscript(row)
            val segment = SegmentEntity(episode.id, row.revision, 0, 5000, "Saved text", 0)
            val coverage = CoverageEntity(episode.id, row.revision, 0, 5000)
            dao.putSegments(listOf(segment)); dao.putCoverage(coverage)
            graph.transcription.recover()
            val manager = WorkManager.getInstance(context)
            val name = "transcript:${episode.id}:${row.revision}:0"
            val request = manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).single { !it.state.isFinished }
            val entered = CompletableDeferred<Unit>()
            val repository = TranscriptionRepository(graph, onlineLookup = { _, _ ->
                entered.complete(Unit)
                awaitCancellation() // Work still in progress when Android ends the job at its time limit.
            })
            val refused = TranscriptExecution({ false }, { throw android.app.ForegroundServiceStartNotAllowedException("Synthetic") }, true)
            val run = launch { repository.process(episode.id, row.revision, refused) }
            withTimeout(5000) { entered.await() }
            assertEquals("processing", dao.transcript(episode.id)!!.state)
            run.cancel() // CoroutineWorker.onStopped cancels doWork.
            withTimeout(5000) { run.join() }
            val stopped = dao.transcript(episode.id)!!
            assertEquals("queued", stopped.state)
            assertNull(stopped.error)
            assertEquals(row.revision, stopped.revision)
            assertEquals(5000L, stopped.processedMs)
            assertEquals(listOf(segment), dao.segments(episode.id, row.revision))
            assertEquals(listOf(coverage), dao.coverage(episode.id, row.revision))
            // Nothing cancelled the request: WorkManager reruns it, and recovery keeps that same request.
            graph.transcription.recover()
            assertEquals(request.id, manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).single { !it.state.isFinished }.id)
            // The rerun is refused again and finishes the revision from its saved chunks.
            assertEquals(TranscriptOutcome.COMPLETED, completingRepository(graph).process(episode.id, row.revision, refused))
            assertEquals("ready", dao.transcript(episode.id)!!.state)
            assertEquals(row.revision, dao.transcript(episode.id)!!.revision)
            assertEquals(listOf(segment), dao.segments(episode.id, row.revision))
        } finally { graph.db.close() }
    }

    @Test fun `a pause during a chunk whose start was refused stays paused`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            val row = TranscriptEntity(episode.id, "paused-after-refusal", episode.mediaFingerprint, "tiny", "auto",
                processedMs = 5000, durationMs = 10000, lookupOnline = true)
            dao.putTranscript(row)
            dao.putCoverage(CoverageEntity(episode.id, row.revision, 0, 5000))
            val entered = CompletableDeferred<Unit>()
            val repository = TranscriptionRepository(graph, onlineLookup = { _, _ -> entered.complete(Unit); awaitCancellation() })
            val run = launch { repository.process(episode.id, row.revision, TranscriptExecution({ false }, {
                throw android.app.ForegroundServiceStartNotAllowedException("Synthetic")
            }, true)) }
            withTimeout(5000) { entered.await() }
            repository.pause(episode.id)
            withTimeout(5000) { run.join() }
            assertTrue(run.isCancelled)
            assertEquals("paused", dao.transcript(episode.id)!!.state)
            assertNull(dao.transcript(episode.id)!!.error)
            repository.recover()
            repository.prepareAutomatically(episode.id)
            repository.resumeBlocked()
            assertEquals("paused", dao.transcript(episode.id)!!.state)
            assertEquals(5000L, dao.coverage(episode.id, row.revision).single().endMs)
            assertTrue(WorkManager.getInstance(context).getWorkInfosByTag("transcript:${episode.id}").get(5, TimeUnit.SECONDS)
                .all { it.state.isFinished })
        } finally { graph.db.close() }
    }

    @Test fun `startup resumes transcripts an earlier version paused for a refused start and keeps other pauses`() = runBlocking<Unit> {
        // Startup usually runs in the background, where Android refuses the direct session too.
        val rejected = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? =
                throw android.app.ForegroundServiceStartNotAllowedException("Synthetic startup rejection")
        }
        val graph = isolatedGraph(rejected)
        try {
            val dao = graph.db.library()
            val others = listOf(
                EpisodeEntity("user-paused", episode.podcastId, "g2", "Paused by the user", "https://example.test/2.mp3", mediaFingerprint = "a2", durationMs = 10000),
                EpisodeEntity("time-limit", episode.podcastId, "g3", "Paused at the time limit", "https://example.test/3.mp3", mediaFingerprint = "a3", durationMs = 10000),
                EpisodeEntity("audio-changed", episode.podcastId, "g4", "Audio changed since", "https://example.test/4.mp3", mediaFingerprint = "a4-new", durationMs = 10000),
                EpisodeEntity("failed", episode.podcastId, "g5", "Failed", "https://example.test/5.mp3", mediaFingerprint = "a5", durationMs = 10000))
            others.forEach { dao.putEpisode(it) }
            val blocked = TranscriptEntity(episode.id, "blocked", episode.mediaFingerprint, "tiny", "auto", state = "paused",
                error = LEGACY_TRANSCRIPTION_BLOCKED_MESSAGE, processedMs = 5000, durationMs = 10000)
            val kept = listOf(
                TranscriptEntity("user-paused", "user", "a2", "tiny", "auto", state = "paused", processedMs = 5000),
                TranscriptEntity("time-limit", "limit", "a3", "tiny", "auto", state = "paused",
                    error = "Android's processing time limit was reached. Resume to continue from the saved chunks.", processedMs = 5000),
                TranscriptEntity("audio-changed", "changed", "a4-old", "tiny", "auto", state = "paused",
                    error = LEGACY_TRANSCRIPTION_BLOCKED_MESSAGE, processedMs = 5000),
                TranscriptEntity("failed", "failed", "a5", "tiny", "auto", state = "failed", error = LEGACY_TRANSCRIPTION_BLOCKED_MESSAGE))
            dao.putTranscript(blocked)
            dao.putCoverage(CoverageEntity(episode.id, blocked.revision, 0, 5000))
            dao.putSegments(listOf(SegmentEntity(episode.id, blocked.revision, 0, 5000, "Saved text", 0)))
            kept.forEach { dao.putTranscript(it) }
            graph.transcription.resumeBlocked()
            val resumed = dao.transcript(episode.id)!!
            assertEquals("queued", resumed.state)
            assertNull(resumed.error)
            assertEquals(blocked.revision, resumed.revision)
            assertEquals(5000L, resumed.processedMs)
            assertEquals("Saved text", dao.segments(episode.id, blocked.revision).single().text)
            // Never queued without work: its next chunk waits as one constrained WorkManager request.
            val work = WorkManager.getInstance(context).getWorkInfosByTag("transcript-revision:${episode.id}:${blocked.revision}")
                .get(5, TimeUnit.SECONDS).filter { !it.state.isFinished }
            assertEquals(1, work.size)
            assertTrue(work.single().constraints.requiresBatteryNotLow())
            for (row in kept) {
                assertEquals(row, dao.transcript(row.episodeId)!!.copy(localCreatedAt = row.localCreatedAt))
                assertTrue(WorkManager.getInstance(context).getWorkInfosByTag("transcript:${row.episodeId}").get(5, TimeUnit.SECONDS).isEmpty())
            }
        } finally { graph.db.close() }
    }

    @Test fun `startup rejection cannot overwrite a newer control result`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            val row = TranscriptEntity(episode.id, "guarded-start", episode.mediaFingerprint, "tiny", "auto", localCreatedAt = 1000, lookupOnline = true)
            // The refused start runs on only for the row it started with: the lookup must never run.
            val repository = TranscriptionRepository(graph, onlineLookup = { _, _ -> throw AssertionError("The pass continued") })
            for (replacement in listOf(row.copy(state = "paused"), row.copy(state = "ready"),
                row.copy(state = "partial"), row.copy(state = "stale"), row.copy(revision = "new"))) {
                dao.putTranscript(row, createdAt = 1000)
                val outcome = repository.process(episode.id, row.revision, TranscriptExecution({ false }, {
                    dao.putTranscript(replacement, createdAt = 1000)
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

    @Test fun `actual worker continues past a rejected foreground future at every attempt`() = runBlocking<Unit> {
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
            // The worker carries on without the notification. No model is installed here, so the
            // pass ends at the model check: proof that it ran, not a retry of the refused start.
            for (attempt in listOf(0, 4, 7)) {
                dao.putTranscript(row)
                assertEquals(androidx.work.ListenableWorker.Result.success(), worker(attempt).doWork())
                assertEquals("waiting_model", dao.transcript(episode.id)!!.state)
                assertEquals(row.revision, dao.transcript(episode.id)!!.revision)
                assertEquals(5000L, dao.transcript(episode.id)!!.processedMs)
                assertEquals(5000L, dao.coverage(episode.id, row.revision).single().endMs)
            }
            dao.putTranscript(row)
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
                }, true))
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

    @Test fun `cancellation after a refused start while controls are held keeps resumed work queued`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            val row = TranscriptEntity(episode.id, "locked-recovery", episode.mediaFingerprint, "tiny", "auto")
            dao.putTranscript(row)
            val repository = graph.transcription
            // Hold the real control mutex so the pass, continuing past the refused start, is cancelled
            // while it waits for controls (the model check or its cleanup) and before any mutation.
            val field = TranscriptionRepository::class.java.getDeclaredField("controls").apply { isAccessible = true }
            val controls = field.get(repository) as kotlinx.coroutines.sync.Mutex
            val entered = CompletableDeferred<Unit>()
            val reject = CompletableDeferred<Unit>()
            val old = launch {
                repository.process(episode.id, row.revision, TranscriptExecution({ false }, {
                    entered.complete(Unit)
                    reject.await()
                    throw android.app.ForegroundServiceStartNotAllowedException("Synthetic")
                }, true))
            }
            withTimeout(5000) { entered.await() }
            controls.lock()
            try {
                reject.complete(Unit)
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
