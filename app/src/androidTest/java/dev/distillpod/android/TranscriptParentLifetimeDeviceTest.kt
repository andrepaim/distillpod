package dev.distillpod.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import dev.distillpod.android.data.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic Room protocol; physical execution requires the isolated offline namespace. */
@RunWith(AndroidJUnit4::class)
class TranscriptParentLifetimeDeviceTest {
    @Test fun deletedParentDoesNotCrashOrReappearAndLaterWorkCanStart() = staleStartup("deleted")
    @Test fun userPauseAfterSnapshotRemainsAuthoritative() = staleStartup("paused")
    @Test fun replacementRevisionAndItsCheckpointArePreserved() = staleStartup("replacement")
    @Test fun terminalWorkAfterSnapshotRemainsReady() = staleStartup("ready")
    @Test fun newerCheckpointOnTheSameRevisionIsNotRewoundByStartup() = staleStartup("checkpoint")

    private fun staleStartup(change: String) = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val phone = context.packageName in setOf("dev.distillpod.android.validation.lifetime81", "dev.distillpod.android.validation.lifetime81r2")
        check(phone || Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic")) {
            "Physical execution requires the dedicated lifetime validation app"
        }
        if (phone) for (permission in listOf(Manifest.permission.INTERNET, Manifest.permission.RECORD_AUDIO))
            check(context.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED)
        val tag = "lifetime81-" + UUID.randomUUID()
        val inactive = SupervisorJob().apply { cancel() }
        val graph = AppGraph(context, CoroutineScope(inactive + Dispatchers.Main.immediate))
        val dao = graph.db.library()
        val episode = EpisodeEntity(tag, tag, "fixture", "Synthetic lifetime fixture",
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
            if (phone) check(dao.episodes().isEmpty() && dao.queue().isEmpty() && dao.transcripts().isEmpty()) {
                "Use a fresh offline lifetime validation app"
            }
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
            // Only this UUID-scoped synthetic parent; never clear an application database.
            dao.deletePodcast(episode.podcastId)
            graph.db.close()
        }
    }
}
