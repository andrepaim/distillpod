package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.ai.*
import dev.distillpod.android.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureRepositoryTest {
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    private suspend fun seed(db: AppDatabase) {
        val dao = db.library()
        dao.putPodcast(PodcastEntity("pod", "https://example.test/feed", "Show"))
        dao.putEpisode(EpisodeEntity("ep", "pod", "guid", "Episode", "https://example.test/audio", durationMs = 100000, mediaFingerprint = "sha"))
        dao.putTranscript(TranscriptEntity("ep", "revision", "sha", "tiny", "en", state = "ready", durationMs = 100000, processedMs = 100000))
        dao.putCoverage(CoverageEntity("ep", "revision", 0, 100000))
        dao.putSegments(listOf(SegmentEntity("ep", "revision", 1000, 10000, "Small daily routines help build lasting skills.")))
    }
    private fun ai(body: String) = object : AiGateway { override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit) = AiText(body, "test-model") }

    @Test fun `capture is durable and duplicate taps keep one original source position`() = runBlocking {
        val db = database()
        try {
            seed(db); val requests = mutableListOf<String>()
            val repo = CaptureRepository(db, ai("""{"quote":"Small daily routines","insight":"Consistent routines help turn practice into lasting skills."}"""), { true }, {}, requests::add)
            val id = repo.capture("ep", 10000, true)
            assertEquals(id, repo.capture("ep", 10005, true))
            assertEquals(1, db.library().distillations().size)
            assertEquals(10000L, db.library().distillation(id)!!.endMs)
            db.library().progress("ep", 90000, 100000, false, System.currentTimeMillis())
            repo.run(id)
            val saved = db.library().distillation(id)!!
            assertEquals(10000L, saved.endMs); assertEquals("Small daily routines", saved.quote)
            assertEquals("succeeded", saved.state); assertEquals("test-model", saved.model)
        } finally { db.close() }
    }
    @Test fun `quota retains the excerpt and does not automatically enqueue retries`() = runBlocking {
        val db = database()
        try {
            seed(db); var calls = 0; var requests = 0
            val gateway = object : AiGateway { override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit): AiText { calls++; throw QuotaExceeded() } }
            val repo = CaptureRepository(db, gateway, { true }, {}, { requests++ })
            val id = repo.capture("ep", 10000, true)
            repo.run(id)
            assertEquals("waiting_quota", db.library().distillation(id)!!.state)
            assertTrue(db.library().distillation(id)!!.excerpt.contains("Small daily routines"))
            assertEquals(1, calls); assertEquals(1, requests)
        } finally { db.close() }
    }
    @Test fun `a streamed capture requires source review before an AI request`() = runBlocking {
        val db = database()
        try {
            seed(db); var requests = 0
            val repo = CaptureRepository(db, ai("{}"), { true }, {}, { requests++ })
            val id = repo.capture("ep", 10000, false)
            assertEquals("review_source", db.library().distillation(id)!!.state); assertEquals(0, requests)
            repo.generate(id, true)
            assertEquals(1, requests)
        } finally { db.close() }
    }
    @Test fun `coverage gaps prepare transcripts without fabricating an excerpt`() = runBlocking {
        val db = database()
        try {
            seed(db); db.library().deleteCoverage("ep"); var prepared = false
            val repo = CaptureRepository(db, ai("{}"), { true }, { prepared = true }, {})
            val id = repo.capture("ep", 10000, true)
            assertTrue(prepared); assertEquals("waiting_transcript", db.library().distillation(id)!!.state)
            assertEquals("", db.library().distillation(id)!!.excerpt)
        } finally { db.close() }
    }
    @Test fun `a fabricated quote is rejected while preserving the saved source`() = runBlocking {
        val db = database()
        try {
            seed(db)
            val repo = CaptureRepository(db, ai("""{"quote":"Invented material","insight":"This is an unsupported invented claim."}"""), { true }, {}, {})
            val id = repo.capture("ep", 10000, true); repo.run(id)
            val saved = db.library().distillation(id)!!
            assertEquals("failed", saved.state); assertNull(saved.quote); assertTrue(saved.excerpt.isNotBlank())
        } finally { db.close() }
    }
    @Test fun `a process interrupted attempt never silently repeats an AI request`() = runBlocking {
        val db = database()
        try {
            seed(db); var calls = 0
            val gateway = object : AiGateway { override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit): AiText { calls++; error("Should require retry") } }
            val repo = CaptureRepository(db, gateway, { true }, {}, {})
            val id = repo.capture("ep", 10000, true)
            db.library().putDistillation(db.library().distillation(id)!!.copy(state = "running"))
            EpisodeUnderstanding(db, gateway, {}, {}).recoverInterrupted()
            repo.run(id)
            assertEquals(0, calls); assertEquals("failed", db.library().distillation(id)!!.state)
            assertTrue(db.library().distillation(id)!!.excerpt.isNotBlank())
        } finally { db.close() }
    }
}
