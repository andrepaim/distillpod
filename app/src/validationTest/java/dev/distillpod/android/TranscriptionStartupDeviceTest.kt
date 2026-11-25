package dev.distillpod.android

import android.app.ForegroundServiceStartNotAllowedException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Opt-in synthetic probe. Never runs against the production application or accounts. */
@RunWith(AndroidJUnit4::class)
class TranscriptionStartupDeviceTest {
    private suspend fun fixture(block: suspend (AppGraph, TranscriptEntity) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.distillpod.android.validation.startup56")
        check(android.os.Build.VERSION.SDK_INT >= 31)
        val graph = context.appGraph
        graph.ready.await()
        val dao = graph.db.library()
        val id = "startup-fixture-" + UUID.randomUUID()
        val row = TranscriptEntity(id, id, id, "missing-startup-fixture-model", "auto",
            durationMs = 10000, processedMs = 5000, lookupOnline = true, localCreatedAt = 1000)
        val audio = java.io.File.createTempFile("startup-fixture-", ".wav", context.cacheDir)
        dao.putPodcast(PodcastEntity(id, "https://example.invalid/" + id, "Synthetic startup fixture"))
        dao.putEpisode(EpisodeEntity(id, id, id, "Synthetic episode", "https://example.invalid/audio",
            mediaFingerprint = id, durationMs = 10000, localFile = audio.absolutePath))
        dao.putTranscript(row, createdAt = 1000)
        dao.putSegments(listOf(SegmentEntity(id, id, 0, 5000, "Synthetic checkpoint", 0)))
        dao.putCoverage(CoverageEntity(id, id, 0, 5000))
        var failure: Throwable? = null
        try { block(graph, row) }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            try {
                graph.transcription.pause(id)
                try {
                    withTimeout(15000) {
                        while (dev.distillpod.android.work.TranscriptionService.isRunning) delay(50)
                    }
                } catch (timeout: TimeoutCancellationException) {
                    throw AssertionError("Startup fixture cleanup: foreground service did not stop", timeout)
                }
                dao.deletePodcast(id)
                check(audio.delete()) { "Startup fixture audio cleanup failed" }
            } catch (cleanup: Throwable) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    /** #301: a refused start no longer retries; the chunk runs on as ordinary work and completes. */
    @Test fun rejectedStartupContinuesAndCompletesWithSavedRevision() = runBlocking<Unit> {
        fixture { graph, row ->
            val dao = graph.db.library()
            val repository = TranscriptionRepository(graph, onlineLookup = { _, current ->
                dao.putTranscript(current.copy(state = "ready"))
                true
            })
            val outcome = repository.process(row.episodeId, row.revision, TranscriptExecution({ false }, {
                throw ForegroundServiceStartNotAllowedException("Synthetic startup rejection")
            }, true))
            assertEquals(TranscriptOutcome.COMPLETED, outcome)
            assertEquals(androidx.work.ListenableWorker.Result.success(), outcome.workerResult())
            assertEquals("ready", dao.transcript(row.episodeId)!!.state)
            assertEquals(row.revision, dao.transcript(row.episodeId)!!.revision)
            assertEquals("Synthetic checkpoint", dao.segments(row.episodeId, row.revision).single().text)
            assertEquals(5000L, dao.coverage(row.episodeId, row.revision).single().endMs)
        }
    }

    @Test fun repeatedRejectionNeverPausesAndKeepsCheckpoints() = runBlocking<Unit> {
        fixture { graph, row ->
            val dao = graph.db.library()
            // No lookup result and no model: each pass reaches the model check, proving it ran.
            val repository = TranscriptionRepository(graph, onlineLookup = { _, _ -> false })
            repeat(6) {
                dao.putTranscript(row, createdAt = 1000)
                val outcome = repository.process(row.episodeId, row.revision, TranscriptExecution({ false }, {
                    throw java.util.concurrent.ExecutionException(ForegroundServiceStartNotAllowedException("Synthetic"))
                }, true))
                assertEquals(TranscriptOutcome.COMPLETED, outcome)
                assertEquals("waiting_model", dao.transcript(row.episodeId)!!.state)
            }
            assertEquals(row.revision, dao.transcript(row.episodeId)!!.revision)
            assertEquals(5000L, dao.coverage(row.episodeId, row.revision).single().endMs)
        }
    }

    @Test fun rejectionCannotUndoPauseReplacementOrCompletion() = runBlocking<Unit> {
        fixture { graph, row ->
            val dao = graph.db.library()
            for (replacement in listOf(row.copy(state = "paused"), row.copy(revision = "new"),
                row.copy(state = "ready"), row.copy(state = "stale"), row.copy(state = "partial"))) {
                dao.putTranscript(row, createdAt = 1000)
                // A refused start continues only for the row it started with; the lookup never runs here.
                val repository = TranscriptionRepository(graph, onlineLookup = { _, _ -> throw AssertionError("The pass continued") })
                val result = repository.process(row.episodeId, row.revision, TranscriptExecution({ false }, {
                    dao.putTranscript(replacement, createdAt = 1000)
                    throw ForegroundServiceStartNotAllowedException("Synthetic")
                }, true))
                assertEquals(TranscriptOutcome.COMPLETED, result)
                assertEquals(replacement, dao.transcript(row.episodeId))
            }
        }
    }
    @Test fun activityResumeStartsRealForegroundSessionAndKeepsUserPause() = runBlocking<Unit> {
        fixture { graph, row ->
            val dao = graph.db.library()
            // Queued, as a refused background start leaves it; the visible activity starts the session.
            dao.putTranscript(row.copy(lookupOnline = false))
            androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { activity ->
                // MainActivity.onResume starts the real service; missing model prevents network or inference.
                try {
                    withTimeout(15000) {
                        while (dao.transcript(row.episodeId)?.state != "waiting_model") delay(50)
                    }
                } catch (timeout: TimeoutCancellationException) {
                    val current = dao.transcript(row.episodeId)
                    val network = current?.let { transcriptionNeedsNetwork(dao.episode(row.episodeId), it) }
                    val wait = network?.let { transcriptionWaitReason(graph.context, graph.settings.flow.first(), it) }
                    throw AssertionError("Activity recovery did not reach waiting_model: state=${current?.state}, " +
                        "sameRevision=${current?.revision == row.revision}, needsNetwork=$network, wait=$wait, " +
                        "serviceRunning=${dev.distillpod.android.work.TranscriptionService.isRunning}", timeout)
                }
                assertEquals(row.revision, dao.transcript(row.episodeId)!!.revision)
                assertEquals(5000L, dao.transcript(row.episodeId)!!.processedMs)
                activity.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                graph.transcription.pause(row.episodeId)
                activity.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                graph.transcription.resumeQueued()
                assertEquals("paused", dao.transcript(row.episodeId)!!.state)
                assertEquals("Synthetic checkpoint", dao.segments(row.episodeId, row.revision).single().text)
            }
        }
    }

}
