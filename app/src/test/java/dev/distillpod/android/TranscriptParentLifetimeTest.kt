package dev.distillpod.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.android.data.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptParentLifetimeTest {
    @Test fun deletedParentDoesNotCrashOrReappearAndLaterWorkCanStart() = staleStartup("deleted")
    @Test fun userPauseAfterSnapshotRemainsAuthoritative() = staleStartup("paused")
    @Test fun replacementRevisionAndItsCheckpointArePreserved() = staleStartup("replacement")
    @Test fun terminalWorkAfterSnapshotRemainsReady() = staleStartup("ready")
    @Test fun newerCheckpointOnTheSameRevisionIsNotRewoundByStartup() = staleStartup("checkpoint")

    private fun staleStartup(change: String) = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val inactive = SupervisorJob().apply { cancel() }
        val graph = AppGraph(context, CoroutineScope(inactive + Dispatchers.Main.immediate))
        val dao = graph.db.library()
        val episode = EpisodeEntity("lifetime-episode", "lifetime-show", "fixture", "Synthetic lifetime fixture",
            "https://example.test/audio", mediaFingerprint = "fixture", durationMs = 10000)
        val row = TranscriptEntity(episode.id, "original", "fixture", "missing-lifetime-model", "en",
            processedMs = 1234, lookupOnline = false, localCreatedAt = 1000)
        val fetched = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = AtomicBoolean(true)
        val gated = object : LibraryDao by dao {
            override suspend fun transcript(id: String): TranscriptEntity? {
                val snapshot = dao.transcript(id)
                if (id == episode.id && first.compareAndSet(true, false)) {
                    fetched.complete(Unit)
                    release.await()
                }
                return snapshot
            }
        }
        var foregroundStarts = 0
        val repository = TranscriptionRepository(graph, gated, { _, _ -> false })
        try {
            dao.putPodcast(PodcastEntity(episode.podcastId, "https://example.test/feed", "Synthetic lifetime fixture"))
            dao.putEpisode(episode)
            dao.putTranscript(row, createdAt = 1000)
            val work = async {
                runCatching { repository.process(episode.id, row.revision,
                    TranscriptExecution({ false }, { foregroundStarts++ }, false)) }
            }
            val replacement = when (change) {
                "replacement" -> row.copy(revision = "new-revision", state = "queued", processedMs = 5678)
                "paused" -> row.copy(state = "paused", processedMs = 5678)
                "checkpoint" -> row.copy(processedMs = 5678)
                else -> row.copy(state = "ready", processedMs = 10000)
            }
            try {
                withTimeout(5000) { fetched.await() }
                if (change == "deleted") dao.deletePodcast(episode.podcastId)
                else dao.putTranscript(replacement, createdAt = 1000)
            } finally { release.complete(Unit) }
            assertEquals(TranscriptOutcome.COMPLETED, withTimeout(5000) { work.await() }.getOrThrow())
            if (change == "checkpoint") {
                assertEquals(1, foregroundStarts)
                assertEquals("waiting_model", dao.transcript(episode.id)!!.state)
                assertEquals(5678L, dao.transcript(episode.id)!!.processedMs)
                return@runBlocking
            }
            assertEquals("Stale work must not start foreground processing", 0, foregroundStarts)
            assertFalse(repository.progress.value.containsKey(episode.id))
            if (change == "deleted") {
                assertNull(dao.episode(episode.id))
                assertNull(dao.transcript(episode.id))
                assertTrue(dao.segments(episode.id, row.revision).isEmpty())
                // A removed request must not poison processing of a later valid episode.
                dao.putPodcast(PodcastEntity(episode.podcastId, "https://example.test/feed", "Synthetic lifetime fixture"))
                dao.putEpisode(episode)
                dao.putTranscript(row.copy(revision = "later-valid"))
                repository.process(episode.id, "later-valid", TranscriptExecution({ false }, { foregroundStarts++ }, false))
                assertEquals(1, foregroundStarts)
                assertEquals("waiting_model", dao.transcript(episode.id)!!.state)
            } else assertEquals(replacement, dao.transcript(episode.id))
        } finally {
            release.complete(Unit)
            graph.db.close()
        }
    }
}
