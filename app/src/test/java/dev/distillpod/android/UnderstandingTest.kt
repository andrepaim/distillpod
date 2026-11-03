package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.ai.*
import dev.distillpod.android.data.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnderstandingTest {
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    private suspend fun seed(db: AppDatabase, long: Boolean = false) {
        val dao = db.library()
        dao.putPodcast(PodcastEntity("p", "https://example.test/feed", "Show"))
        dao.putEpisode(EpisodeEntity("e", "p", "guid", "Episode", "https://example.test/audio", durationMs = 80000, mediaFingerprint = "fingerprint"))
        dao.putTranscript(TranscriptEntity("e", "r", "fingerprint", "tiny", "en", state = "ready", durationMs = 80000, processedMs = 80000))
        dao.putCoverage(CoverageEntity("e", "r", 0, 80000))
        dao.putSegments((0 until if (long) 8 else 1).map { SegmentEntity("e", "r", it * 10000L, (it + 1) * 10000L,
            "Consistent practice builds useful skills." + if (long) " Supporting detail.".repeat(400) else "") })
    }
    @Test fun `long analysis reuses completed parts after quota and normalizes approved ranges`() = runBlocking {
        val db = database()
        try {
            seed(db, true); var calls = 0
            val ai = object : AiGateway {
                override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit): AiText {
                    calls++
                    if (calls == 2) throw QuotaExceeded()
                    if (instructions.startsWith("Combine")) return AiText("""{"summary":"Practice improves skills over time."}""", "fixture")
                    val segments = JSONArray(input); val first = segments.getJSONObject(0)
                    return AiText(JSONObject().put("summary", "Practice improves skills over time.")
                        .put("chapters", JSONArray().put(JSONObject().put("startMs", first.getLong("startMs")).put("title", "Practice")))
                        .put("ads", JSONArray().put(JSONObject().put("startMs", first.getLong("startMs")).put("endMs", first.getLong("endMs")).put("reason", "Fixture promotion"))).toString(), "fixture")
                }
            }
            val repo = EpisodeUnderstanding(db, ai, {}, {})
            repo.analyze("e"); repo.runAnalysis("e")
            assertEquals("waiting_quota", db.library().analysis("e")!!.state)
            assertEquals(1, db.library().analysisParts("e", "r").size)
            repo.analyze("e"); repo.runAnalysis("e")
            val row = db.library().analysis("e")!!
            assertEquals(row.error, "succeeded", row.state)
            val parts = transcriptParts(db.library().segments("e", "r")).size
            assertEquals(parts + 2, calls)
            assertEquals(parts, row.completedParts)
            val ads = JSONArray(row.adsJson)
            assertTrue(ads.length() > 0); assertFalse(ads.getJSONObject(0).getBoolean("approved"))
            repo.approveAd("e", 0, true)
            assertTrue(JSONArray(db.library().analysis("e")!!.adsJson).getJSONObject(0).getBoolean("approved"))
        } finally { db.close() }
    }
    @Test fun `invalid timestamps are rejected instead of becoming seek targets`() {
        val segments = listOf(SegmentEntity("e", "r", 1000, 10000, "Source"))
        assertThrows(IllegalArgumentException::class.java) { validatedAnalysis("""{"summary":"Source summary.","chapters":[{"startMs":1234,"title":"Invented"}],"ads":[]}""", segments) }
        assertThrows(IllegalArgumentException::class.java) { validatedAnalysis("""{"summary":"Source summary.","chapters":[],"ads":[{"startMs":1000,"endMs":5000,"reason":"Not a source boundary"}]}""", segments) }
    }
    @Test fun `chat validates sources and retry reuses one durable question`() = runBlocking {
        val db = database()
        try {
            seed(db); var calls = 0
            val ai = object : AiGateway { override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit): AiText {
                calls++; onDelta("""{"answer":"Practice builds skills""")
                return AiText(if (calls == 1) """{"answer":"Practice builds useful skills.","supported":true,"sources":[999999]}""" else """{"answer":"Practice builds useful skills.","supported":true,"sources":[0]}""", "fixture")
            } }
            val repo = EpisodeUnderstanding(db, ai, {}, {})
            val id = repo.ask("e", "How does practice help?")
            assertEquals(id, repo.ask("e", "How does practice help?"))
            repo.runReply(id)
            assertEquals("failed", db.library().message(id)!!.state)
            assertTrue(db.library().message(id)!!.text.startsWith("Practice"))
            repo.retryReply(id); repo.runReply(id)
            assertEquals("succeeded", db.library().message(id)!!.state)
            assertEquals("[0]", db.library().message(id)!!.sourcesJson)
            assertEquals(1, db.library().messages().count { it.role == "user" })
            assertEquals(2, calls)
        } finally { db.close() }
    }
    @Test fun `late error from a cancelled attempt cannot replace a successful retry`() = runBlocking {
        val db = database()
        try {
            seed(db)
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
            val ai = object : AiGateway { override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit): AiText {
                if (++calls == 1) { started.complete(Unit); release.await(); throw java.io.IOException("Old transport failure") }
                return AiText("""{"answer":"Practice builds useful skills.","supported":true,"sources":[0]}""", "fixture")
            } }
            val repo = EpisodeUnderstanding(db, ai, {}, {})
            val id = repo.ask("e", "How does practice help?")
            val old = launch { repo.runReply(id) }
            started.await()
            db.library().putMessage(db.library().message(id)!!.copy(state = "failed"))
            repo.retryReply(id); repo.runReply(id)
            release.complete(Unit); old.join()
            assertEquals("succeeded", db.library().message(id)!!.state)
        } finally { db.close() }
    }
    @Test fun `partial JSON decoding handles escaped text without publishing source metadata`() {
        assertEquals("Hello\nworld \"yes\"", streamedAnswer("""{"answer":"Hello\nworld \"yes\""""))
        assertEquals("Hello", streamedAnswer("""{"answer":"Hello\u00"""))
        assertEquals("", streamedAnswer("""{"sources":[0]}"""))
    }
    @Test fun `unsupported answer stays visibly qualified and offers no misleading source links`() = runBlocking {
        val db = database()
        try {
            seed(db)
            val ai = object : AiGateway { override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit) =
                AiText("""{"answer":"That information is unavailable here.","supported":false,"sources":[]}""", "fixture") }
            val repo = EpisodeUnderstanding(db, ai, {}, {})
            val id = repo.ask("e", "What did the speaker eat for breakfast?")
            repo.runReply(id)
            val row = db.library().message(id)!!
            assertEquals("succeeded", row.state)
            assertTrue(row.text.startsWith("The selected transcript excerpts do not support"))
            assertEquals("[]", row.sourcesJson)
        } finally { db.close() }
    }
}
