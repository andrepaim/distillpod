package dev.distillpod.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Synthetic repository checks; no playback, model files, account or external network. */
@RunWith(AndroidJUnit4::class)
class TranscriptionPauseDeviceTest {
    private suspend fun fixture(block: suspend (AppGraph, TranscriptEntity) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.distillpod.android.validation.pauserace") {
            "Use the dedicated pause-race validation package on a physical phone"
        }
        val graph = context.appGraph
        graph.ready.await()
        val dao = graph.db.library()
        val id = "pause-fixture-" + UUID.randomUUID()
        val row = TranscriptEntity(id, id, id, "missing-pause-fixture-model", "auto")
        dao.putPodcast(PodcastEntity(id, "https://example.invalid/" + id, "Synthetic pause fixture"))
        dao.putEpisode(EpisodeEntity(id, id, id, "Synthetic episode", "https://example.invalid/audio",
            mediaFingerprint = id, durationMs = 10000))
        dao.putTranscript(row)
        dao.putSegments(listOf(SegmentEntity(id, id, 0, 5000, "Synthetic saved text", 0)))
        dao.putCoverage(CoverageEntity(id, id, 0, 5000))
        try { block(graph, row) }
        finally {
            graph.transcription.pause(id)
            dao.deletePodcast(id)
        }
    }

    @Test fun missingModelDoesNotOverwritePausedSupersededOrTerminalRows() = runBlocking<Unit> {
        fixture { graph, row ->
            val dao = graph.db.library()
            for (current in listOf(row.copy(state = "paused"),
                row.copy(revision = "replacement", state = "queued"),
                row.copy(state = "stale"), row.copy(state = "ready"), row.copy(state = "partial"))) {
                dao.putTranscript(row)
                graph.transcription.process(row.episodeId, row.revision, TranscriptExecution({ false }, {
                    // Commit a newer control result after the worker captured its starting row.
                    dao.putTranscript(current)
                }, false))
                assertEquals(current, dao.transcript(row.episodeId))
                assertEquals("Synthetic saved text", dao.segments(row.episodeId, row.revision).single().text)
                assertEquals(5000L, dao.coverage(row.episodeId, row.revision).single().endMs)
            }
        }
    }

    @Test fun pauseCancelsAnInFlightPassAndKeepsSavedState() = runBlocking<Unit> {
        fixture { graph, row ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coroutineScope {
                val worker = launch {
                    graph.transcription.process(row.episodeId, row.revision, TranscriptExecution({ false }, {
                        entered.complete(Unit)
                        release.await()
                    }, false))
                }
                try {
                    withTimeout(10000) { entered.await() }
                    graph.transcription.pause(row.episodeId)
                } finally { release.complete(Unit) }
                withTimeout(10000) { worker.join() }
            }
            val dao = graph.db.library()
            assertEquals("paused", dao.transcript(row.episodeId)!!.state)
            assertNull(dao.transcript(row.episodeId)!!.error)
            assertFalse(graph.transcription.progress.value.containsKey(row.episodeId))
            assertEquals("Synthetic saved text", dao.segments(row.episodeId, row.revision).single().text)
            assertEquals(5000L, dao.coverage(row.episodeId, row.revision).single().endMs)
            assertTrue("Fresh validation app must retain automatic preparation default",
                graph.settings.flow.first().transcribeOnPlayback)
            graph.transcription.prepareAutomatically(row.episodeId)
            assertEquals("paused", dao.transcript(row.episodeId)!!.state)
        }
    }

    @Test fun missingModelPreservesTheLatestQueuedProgress() = runBlocking<Unit> {
        fixture { graph, row ->
            val dao = graph.db.library()
            val current = row.copy(processedMs = 5000, durationMs = 10000)
            graph.transcription.process(row.episodeId, row.revision, TranscriptExecution({ false }, {
                dao.putTranscript(current)
            }, false))
            assertEquals(current.copy(state = "waiting_model",
                error = "Download the selected transcription model in Settings."), dao.transcript(row.episodeId))
            assertEquals("Synthetic saved text", dao.segments(row.episodeId, row.revision).single().text)
            assertEquals(5000L, dao.coverage(row.episodeId, row.revision).single().endMs)
        }
    }
}
