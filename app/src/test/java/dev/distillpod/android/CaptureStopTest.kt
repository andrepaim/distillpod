package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.ai.AiGateway
import dev.distillpod.android.ai.AiText
import dev.distillpod.android.data.*
import dev.distillpod.android.work.WorkStop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * #261: a car distill's generation is stopped by WorkManager when the network constraint is lost (a Wi-Fi to
 * mobile handover) and rerun when it returns. The stop must not end the moment as "Needs retry".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureStopTest {
    private val candidate = """{"outcome":"ready","passageStart":0,"passageEnd":0,"takeaway":"Small routines build lasting skills.","quoteStart":0,"quoteEnd":0}"""
    private val review = """{"passageComplete":true,"takeawaySupported":true,"relevant":true,"quoteFaithful":true,"needsContext":false,"issues":[]}"""

    /** Fails each listed call once with [failure] (1 = first generation, then counted across calls). */
    private inner class Gateway(private val failOnCall: Int, private val failure: () -> Exception, private val before: suspend () -> Unit = {}) : AiGateway {
        var generations = 0; var reviews = 0; private var calls = 0
        override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit): AiText {
            calls++
            if (calls == failOnCall) { before(); throw failure() }
            return if (instructions == MomentGeneration.reviewPrompt) { reviews++; AiText(review, "review-model") }
            else { generations++; AiText(candidate, "test-model") }
        }
    }

    private suspend fun runStopped(repo: CaptureRepository, id: String, stop: WorkStop) {
        try { repo.run(id, stop = { stop }) } catch (_: CancellationException) {}
    }

    @Test fun `a rescheduled stop during generation queues the moment and the rerun finishes it`() = fixture { db ->
        val gateway = Gateway(1, { CancellationException("Worker stopped: constraints not met") })
        val repo = CaptureRepository(db, gateway, { true }, {}, {})
        val id = repo.capture("ep", 10000, true)
        runStopped(repo, id, WorkStop.RESCHEDULED)
        assertEquals("queued", db.library().distillation(id)!!.state)
        assertNull(db.library().distillation(id)!!.error)
        repo.run(id)
        assertEquals("succeeded", db.library().distillation(id)!!.state)
        assertEquals(1, gateway.generations); assertEquals(1, gateway.reviews)
    }

    @Test fun `a request cut off by the stop is a stop, not a failed transfer`() = fixture { db ->
        val gateway = Gateway(1, { IOException("Canceled") })
        val repo = CaptureRepository(db, gateway, { true }, {}, {})
        val id = repo.capture("ep", 10000, true)
        runStopped(repo, id, WorkStop.RESCHEDULED)
        assertEquals("queued", db.library().distillation(id)!!.state)
        repo.run(id)
        assertEquals("succeeded", db.library().distillation(id)!!.state)
    }

    @Test fun `a stop after generation keeps the candidate and the rerun only reviews`() = fixture { db ->
        val gateway = Gateway(2, { CancellationException("Worker stopped") })
        val repo = CaptureRepository(db, gateway, { true }, {}, {})
        val id = repo.capture("ep", 10000, true)
        runStopped(repo, id, WorkStop.RESCHEDULED)
        assertEquals("queued", db.library().distillation(id)!!.state)
        repo.run(id)
        assertEquals("succeeded", db.library().distillation(id)!!.state)
        assertEquals("The generation request is not repeated after a stop", 1, gateway.generations)
        assertEquals(1, gateway.reviews)
    }

    @Test fun `pause stays paused even when the stop arrives afterwards`() = fixture { db ->
        lateinit var repo: CaptureRepository
        var id = ""
        val gateway = Gateway(1, { CancellationException("Cancelled by the app") }, before = { repo.cancel(id) })
        repo = CaptureRepository(db, gateway, { true }, {}, {})
        id = repo.capture("ep", 10000, true)
        runStopped(repo, id, WorkStop.PAUSED)
        assertEquals("failed", db.library().distillation(id)!!.state)
        assertEquals("Generation paused. Your saved result and source are kept.", db.library().distillation(id)!!.error)
    }

    @Test fun `a real request error that is not a stop still fails`() = fixture { db ->
        val gateway = Gateway(1, { IOException("Connection reset") })
        val repo = CaptureRepository(db, gateway, { true }, {}, {})
        val id = repo.capture("ep", 10000, true)
        repo.run(id, stop = { WorkStop.ENDED })
        assertEquals("failed", db.library().distillation(id)!!.state)
    }

    private fun fixture(block: suspend (AppDatabase) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val dao = db.library()
            dao.putPodcast(PodcastEntity("pod", "https://example.test/feed", "Show"))
            dao.putEpisode(EpisodeEntity("ep", "pod", "guid", "Episode", "https://example.test/audio", durationMs = 100000, mediaFingerprint = "sha"))
            dao.putTranscript(TranscriptEntity("ep", "revision", "sha", "tiny", "en", state = "ready", durationMs = 100000, processedMs = 100000))
            dao.putCoverage(CoverageEntity("ep", "revision", 0, 100000))
            dao.putSegments(listOf(SegmentEntity("ep", "revision", 1000, 10000, "Small daily routines help build lasting skills.")))
            block(db)
        } finally { db.close() }
    }
}
