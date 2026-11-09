package dev.distillpod.android

import android.app.ForegroundServiceStartNotAllowedException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.*
import kotlinx.coroutines.*
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
            durationMs = 10000, processedMs = 5000, lookupOnline = true)
        dao.putPodcast(PodcastEntity(id, "https://example.invalid/" + id, "Synthetic startup fixture"))
        dao.putEpisode(EpisodeEntity(id, id, id, "Synthetic episode", "https://example.invalid/audio",
            mediaFingerprint = id, durationMs = 10000))
        dao.putTranscript(row)
        dao.putSegments(listOf(SegmentEntity(id, id, 0, 5000, "Synthetic checkpoint", 0)))
        dao.putCoverage(CoverageEntity(id, id, 0, 5000))
        try { block(graph, row) }
        finally {
            graph.transcription.pause(id)
            dao.deletePodcast(id)
        }
    }

    @Test fun rejectedStartupRetriesThenCompletesWithSavedRevision() = runBlocking<Unit> {
        fixture { graph, row ->
            val dao = graph.db.library()
            val repository = TranscriptionRepository(graph, onlineLookup = { _, current ->
                dao.putTranscript(current.copy(state = "ready"))
                true
            })
            val rejected = repository.process(row.episodeId, row.revision, TranscriptExecution({ false }, {
                throw ForegroundServiceStartNotAllowedException("Synthetic startup rejection")
            }, true))
            assertEquals(TranscriptOutcome.DEFERRED_START, rejected)
            assertEquals(androidx.work.ListenableWorker.Result.retry(), rejected.workerResult())
            assertEquals("queued", dao.transcript(row.episodeId)!!.state)
            assertEquals(5000L, dao.transcript(row.episodeId)!!.processedMs)
            assertEquals("Synthetic checkpoint", dao.segments(row.episodeId, row.revision).single().text)
            assertEquals(5000L, dao.coverage(row.episodeId, row.revision).single().endMs)
            val completed = repository.process(row.episodeId, row.revision, TranscriptExecution({ false }, {}, true, 1))
            assertEquals(TranscriptOutcome.COMPLETED, completed)
            assertEquals("ready", dao.transcript(row.episodeId)!!.state)
            assertEquals(row.revision, dao.transcript(row.episodeId)!!.revision)
        }
    }

    @Test fun repeatedRejectionRequiresExplicitResumeWithoutLosingCheckpoints() = runBlocking<Unit> {
        fixture { graph, row ->
            val dao = graph.db.library()
            repeat(5) { attempt ->
                val outcome = graph.transcription.process(row.episodeId, row.revision, TranscriptExecution({ false }, {
                    throw java.util.concurrent.ExecutionException(ForegroundServiceStartNotAllowedException("Synthetic"))
                }, true, attempt))
                assertEquals(if (attempt < 4) TranscriptOutcome.DEFERRED_START else TranscriptOutcome.RESUME_REQUIRED, outcome)
            }
            assertEquals("paused", dao.transcript(row.episodeId)!!.state)
            assertTrue(dao.transcript(row.episodeId)!!.error!!.contains("Resume"))
            graph.transcription.prepareAutomatically(row.episodeId)
            graph.transcription.recover()
            assertEquals("paused", dao.transcript(row.episodeId)!!.state)
            assertEquals(5000L, dao.coverage(row.episodeId, row.revision).single().endMs)
        }
    }

    @Test fun rejectionCannotUndoPauseReplacementOrCompletion() = runBlocking<Unit> {
        fixture { graph, row ->
            val dao = graph.db.library()
            for (replacement in listOf(row.copy(state = "paused"), row.copy(revision = "new"),
                row.copy(state = "ready"), row.copy(state = "stale"), row.copy(state = "partial"))) {
                dao.putTranscript(row)
                val result = graph.transcription.process(row.episodeId, row.revision, TranscriptExecution({ false }, {
                    dao.putTranscript(replacement)
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
            dao.putTranscript(row.copy(lookupOnline = false))
            assertEquals(TranscriptOutcome.DEFERRED_START, graph.transcription.process(row.episodeId, row.revision,
                TranscriptExecution({ false }, { throw ForegroundServiceStartNotAllowedException("Synthetic") }, true)))
            androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { activity ->
                // MainActivity.onResume starts the real service; missing model prevents network or inference.
                withTimeout(15000) {
                    while (dao.transcript(row.episodeId)?.state != "waiting_model") delay(50)
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
