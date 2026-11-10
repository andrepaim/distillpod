package dev.distillpod.android

import android.content.Context
import dev.distillpod.android.data.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*

/** Shared JVM/platform protocol; all mutated library rows live in a private in-memory database. */
internal object TranscriptMutationFixture {
    fun run(context: Context, operation: String, change: String) = runBlocking<Unit> {
        val inactive = SupervisorJob().apply { cancel() }
        val graph = AppGraph(context, CoroutineScope(inactive + Dispatchers.Main.immediate))
        val database = androidx.room.Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val dao = database.library()
        val tag = "mutation81-" + java.util.UUID.randomUUID()
        val episode = EpisodeEntity(tag, tag, "fixture", "Synthetic lifetime fixture",
            "https://example.test/audio", mediaFingerprint = "fixture", durationMs = 10000)
        val initial = TranscriptEntity(episode.id, "original", "fixture", "missing-mutation-model", "en",
            processedMs = 1234, localCreatedAt = 1000)
        val fetched = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        val gated = object : LibraryDao by dao {
            override suspend fun transcript(id: String): TranscriptEntity? {
                val snapshot = dao.transcript(id)
                if (id == episode.id && reads.incrementAndGet() == if (operation == "pause") 1 else 2) {
                    fetched.complete(Unit)
                    release.await()
                }
                return snapshot
            }
        }
        val cancellation = CancellationException("Synthetic original cancellation")
        val repository = TranscriptionRepository(graph, gated, { _, _ -> false })
        var work: Deferred<Result<Any>>? = null
        try {
            dao.putPodcast(PodcastEntity(episode.podcastId, "https://example.test/feed", "Synthetic lifetime fixture"))
            dao.putEpisode(episode)
            dao.putTranscript(initial, createdAt = 1000)
            work = async {
                runCatching {
                    if (operation == "pause") repository.pause(episode.id)
                    else if (operation == "pending") repository.pausePending("Synthetic processing limit")
                    else repository.process(episode.id, initial.revision, TranscriptExecution({ false }, {
                        if (operation == "cancel") throw cancellation
                    }, false))
                }
            }
            var expected: TranscriptEntity? = null
            try {
                withTimeout(5000) { fetched.await() }
                val current = checkNotNull(dao.transcript(episode.id))
                if (change == "deleted") dao.deletePodcast(episode.podcastId)
                else {
                    val changed = when (change) {
                        "replacement" -> current.copy(revision = "replacement", state = "queued", processedMs = 5678, error = "Keep replacement")
                        "paused", "ready" -> current.copy(state = change, processedMs = 5678, error = "Keep newer state")
                        else -> current.copy(processedMs = 5678, error = "Keep checkpoint")
                    }
                    dao.putTranscript(changed, createdAt = 2000)
                    expected = checkNotNull(dao.transcript(episode.id))
                    if (change == "checkpoint") expected = expected.copy(
                        state = when (operation) { "pause", "pending" -> "paused"; "cancel" -> "queued"; else -> "waiting_model" },
                        error = when (operation) {
                            "model" -> "Download the selected transcription model in Settings."
                            "pending" -> "Synthetic processing limit"
                            else -> null
                        })
                }
            } finally { release.complete(Unit) }
            val result = withTimeout(5000) { work.await() }
            if (operation == "cancel") assertSame("Cleanup must preserve the original cancellation", cancellation, result.exceptionOrNull())
            else result.getOrThrow()
            assertEquals("A stale snapshot must not overwrite newer persisted work", expected, dao.transcript(episode.id))
            if (change == "deleted") {
                assertNull(dao.episode(episode.id))
                assertTrue(dao.segments(episode.id, initial.revision).isEmpty())
                assertTrue(dao.coverage(episode.id, initial.revision).isEmpty())
                dao.putPodcast(PodcastEntity(episode.podcastId, "https://example.test/feed", "Synthetic lifetime fixture"))
                dao.putEpisode(episode)
                dao.putTranscript(initial.copy(revision = "later-valid"), createdAt = 2000)
                var starts = 0
                repository.process(episode.id, "later-valid", TranscriptExecution({ false }, { starts++ }, false))
                assertEquals("Later valid work still starts", 1, starts)
                assertEquals("waiting_model", dao.transcript(episode.id)!!.state)
            }
        } finally {
            release.complete(Unit)
            work?.cancelAndJoin()
            database.close()
            graph.db.close()
        }
    }
}
