package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.ai.*
import dev.distillpod.android.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureRepositoryTest {
    @Test fun `car save commits before slow preparation and pending work survives restart`() = runBlocking {
        val db=database()
        try {
            seed(db);var preparations=0
            val repo=CaptureRepository(db,ai("unused"),{false},{preparations++},{})
            val id=repo.save("ep",10000,true)
            assertEquals(0,preparations);assertNotNull(db.library().distillation(id))
            assertEquals(id,repo.save("ep",10000,true))
            repo.resumeSaved()
            assertTrue(db.library().distillation(id)!!.state!="waiting_transcript")
            assertEquals(0,preparations)
        } finally {db.close()}
    }
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    private suspend fun seed(db: AppDatabase) {
        val dao = db.library()
        dao.putPodcast(PodcastEntity("pod", "https://example.test/feed", "Show"))
        dao.putEpisode(EpisodeEntity("ep", "pod", "guid", "Episode", "https://example.test/audio", durationMs = 100000, mediaFingerprint = "sha"))
        dao.putTranscript(TranscriptEntity("ep", "revision", "sha", "tiny", "en", state = "ready", durationMs = 100000, processedMs = 100000))
        dao.putCoverage(CoverageEntity("ep", "revision", 0, 100000))
        dao.putSegments(listOf(SegmentEntity("ep", "revision", 1000, 10000, "Small daily routines help build lasting skills.")))
    }
    private fun ai(body: String) = object : AiGateway {
        override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit): AiText {
            if(instructions==MomentGeneration.reviewPrompt)return AiText(review,"review-model")
            if(body.isBlank() || body=="{}")return AiText(body,"test-model")
            val old=JSONObject(body)
            val result=JSONObject().put("outcome","ready").put("passageStart",0).put("passageEnd",0)
                .put("takeaway",old.getString("insight")).put("quoteStart",if(old.getString("quote")=="Invented material")99 else 0).put("quoteEnd",0)
            return AiText(result.toString(),"test-model")
        }
    }
    private val review="""{"passageComplete":true,"takeawaySupported":true,"relevant":true,"quoteFaithful":true,"needsContext":false,"issues":[]}"""

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
            assertEquals(10000L, saved.endMs); assertEquals("Small daily routines help build lasting skills.", saved.quote)
            assertEquals("succeeded", saved.state); assertEquals("test-model", saved.model)
        } finally { db.close() }
    }
    @Test fun `capture source and finished insight are durable portable changes`() = runBlocking {
        val db=database()
        try {
            seed(db)
            dev.distillpod.android.sync.SubscriptionSyncStore(db).bind(dev.distillpod.core.sync.SyncOwner("alice","g1"),setOf("pod"))
            val repo=CaptureRepository(db,ai("""{"quote":"Small daily routines","insight":"Consistent routines help turn practice into lasting skills."}"""),{true},{},{})
            val id=repo.capture("ep",10000,true)
            val before=db.sync().forKey("alice","g1","moment_$id").single()
            assertEquals("Small daily routines help build lasting skills.",dev.distillpod.android.sync.SubscriptionSyncStore.decode(before.payload)["excerpt"])
            repo.run(id)
            val after=db.sync().forKey("alice","g1","moment_$id").single()
            assertEquals("Consistent routines help turn practice into lasting skills.",dev.distillpod.android.sync.SubscriptionSyncStore.decode(after.payload)["insight"])
        } finally {db.close()}
    }
    @Test fun `AI response after moment deletion cannot resurrect the local row or cloud content`()=runBlocking {
        val db=database()
        try {
            seed(db)
            dev.distillpod.android.sync.SubscriptionSyncStore(db).bind(dev.distillpod.core.sync.SyncOwner("alice","g1"),setOf("pod"))
            var id=""
            val gateway=object:AiGateway {override suspend fun complete(instructions:String,input:String,onDelta:(String)->Unit):AiText {
                dev.distillpod.android.sync.PortableLibrarySync(db).deleteMoment(id)
                return AiText("""{"outcome":"ready","passageStart":0,"passageEnd":0,"takeaway":"Consistent routines help turn practice into lasting skills.","quoteStart":0,"quoteEnd":0}""","test-model")
            }}
            val repo=CaptureRepository(db,gateway,{true},{},{})
            id=repo.capture("ep",10000,true);repo.run(id)
            assertNull(db.library().distillation(id))
            assertTrue(db.sync().forKey("alice","g1","moment_$id").single().deleted)
        } finally {db.close()}
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
