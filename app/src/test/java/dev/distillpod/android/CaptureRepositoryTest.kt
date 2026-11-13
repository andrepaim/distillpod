package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.ai.*
import dev.distillpod.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
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
    @Test fun `confirmation occurs after commit once for a new save and never on recovery`() = runBlocking {
        val db=database()
        try {
            seed(db);var confirmations=0
            val repo=CaptureRepository(db,ai("unused"),{false},{},{},onSaved={id->
                runBlocking {
                    val saved=db.library().distillation(id)!!
                    val attempt=db.library().momentAttempt(saved.attempt)!!
                    assertEquals(id,attempt.momentId)
                    assertFalse(JSONObject(attempt.contextJson).getBoolean("expanded"))
                }
                confirmations++
            })
            repo.save("ep",10000,true)
            repo.save("ep",10000,true)
            assertEquals(1,confirmations)
            repo.resumeSaved()
            assertEquals(1,confirmations)
            try {repo.save("missing",12000,true);fail("Missing episode accepted")} catch(_:IllegalStateException) {}
            assertEquals(1,confirmations)
        } finally {db.close()}
    }
    @Test fun `feedback failure cannot turn a committed save into an error`()=runBlocking {
        val db=database()
        try {
            seed(db)
            val repo=CaptureRepository(db,ai("unused"),{false},{},{},onSaved={throw IllegalStateException("Audio unavailable")})
            val id=repo.save("ep",10000,true)
            assertNotNull(db.library().distillation(id))
        } finally {db.close()}
    }
    @Test fun `capture correlation persists original request and explicit retry ancestry`() = runBlocking {
        val db=database()
        val diagnostics=enabledDiagnostics()
        try {
            seed(db)
            val repo=CaptureRepository(db,ai("unused"),{false},{},{},diagnostics=diagnostics)
            val id=repo.capture("ep",10000,true)
            keepInDuplicateWindow(db,id)
            val first=db.library().momentAttempt(db.library().distillation(id)!!.attempt)!!
            val metadata=JSONObject(first.contextJson).getJSONObject("_diagnostics")
            val request=metadata.getString("capture_request_id")
            assertEquals("initial",metadata.getString("retry_trigger"))
            assertFalse(MomentGeneration.input(first.contextJson).contains("_diagnostics"))
            assertFalse(MomentGeneration.input(first.contextJson).contains(request))
            assertEquals(id,repo.save("ep",10000,true))
            assertEquals(request,JSONObject(db.library().momentAttempt(first.id)!!.contextJson).getJSONObject("_diagnostics").getString("capture_request_id"))
            repo.improve(id,true)
            val retry=db.library().momentAttempt(db.library().distillation(id)!!.attempt)!!
            val next=JSONObject(retry.contextJson).getJSONObject("_diagnostics")
            assertNotEquals(first.id,retry.id)
            assertEquals(first.id,next.getString("previous_attempt_id"))
            assertEquals(request,next.getString("capture_request_id"))
            assertEquals("expanded_context",next.getString("retry_trigger"))
            assertTrue(JSONObject(retry.contextJson).getBoolean("expanded"))
        } finally {diagnostics.consent(false);db.close()}
    }
    @Test fun `cancel resume keeps execution ownership and suppresses the old response`()=runBlocking {
        val db=database()
        try {
            seed(db)
            var id=""
            var generations=0
            var reviews=0
            var firstRun=""
            var secondRun=""
            lateinit var repo:CaptureRepository
            val gateway=object:AiGateway {
                override suspend fun complete(instructions:String,input:String,onDelta:(String)->Unit):AiText {
                    if(instructions==MomentGeneration.reviewPrompt) {reviews++;return AiText(review,"review-model")}
                    generations++
                    val current=db.library().momentAttempt(db.library().distillation(id)!!.attempt)!!
                    if(generations==1) {
                        firstRun=current.run
                        repo.cancel(id);repo.generate(id);repo.run(id)
                        return AiText("""{"outcome":"ready","passageStart":0,"passageEnd":0,"takeaway":"Old response","quoteStart":null,"quoteEnd":null}""","old-model")
                    }
                    secondRun=current.run
                    return AiText("""{"outcome":"ready","passageStart":0,"passageEnd":0,"takeaway":"Current response","quoteStart":null,"quoteEnd":null}""","new-model")
                }
            }
            repo=CaptureRepository(db,gateway,{true},{},{})
            id=repo.capture("ep",10000,true)
            val attemptId=db.library().distillation(id)!!.attempt
            repo.run(id)
            assertNotEquals(firstRun,secondRun)
            assertEquals(attemptId,db.library().distillation(id)!!.attempt)
            assertEquals("Current response",db.library().distillation(id)!!.insight)
            assertEquals(2,generations);assertEquals(1,reviews)
            assertEquals(secondRun,db.library().momentAttempt(attemptId)!!.run)
        } finally {db.close()}
    }
    @Test fun `review resume retains generation identity but has new execution evidence`()=runBlocking {
        val db=database()
        val diagnostics=enabledDiagnostics()
        try {
            seed(db)
            var generations=0;var reviews=0
            val gateway=object:AiGateway {
                override suspend fun complete(instructions:String,input:String,onDelta:(String)->Unit):AiText {
                    if(instructions==MomentGeneration.reviewPrompt) {
                        reviews++
                        if(reviews==1)throw java.io.IOException("synthetic")
                        return AiText(review,"review-model")
                    }
                    generations++
                    return AiText("""{"outcome":"ready","passageStart":0,"passageEnd":0,"takeaway":"A supported idea","quoteStart":null,"quoteEnd":null}""","test-model")
                }
            }
            val repo=CaptureRepository(db,gateway,{true},{},{},diagnostics=diagnostics)
            val id=repo.capture("ep",10000,true)
            repo.run(id)
            val first=db.library().momentAttempt(db.library().distillation(id)!!.attempt)!!
            repo.generate(id);repo.run(id)
            val resumed=db.library().momentAttempt(db.library().distillation(id)!!.attempt)!!
            assertEquals(first.id,resumed.id);assertNotEquals(first.run,resumed.run)
            val correlation=repo.correlation(resumed)
            assertEquals("review_resume",correlation.retryTrigger)
            assertEquals(true,correlation.candidateReused)
            assertEquals(1,generations);assertEquals(2,reviews)
            assertEquals("succeeded",db.library().distillation(id)!!.state)
        } finally {diagnostics.consent(false);db.close()}
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `worker suspended after claim cannot adopt a resumed workers execution`()=runBlocking {
        val db=database()
        val release=CompletableDeferred<Unit>()
        val entered=CompletableDeferred<Unit>()
        val dispatcher=StandardTestDispatcher()
        val context=ApplicationProvider.getApplicationContext<Context>()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val diagnostics=dev.distillpod.android.diagnostics.Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1")
        diagnostics.consent(false);diagnostics.consent(true)
        try {
            seed(db)
            var generations=0
            val gateway=object:AiGateway {
                override suspend fun complete(instructions:String,input:String,onDelta:(String)->Unit):AiText {
                    if(instructions==MomentGeneration.reviewPrompt)return AiText(review,"review")
                    generations++
                    if(generations==1) {entered.complete(Unit);release.await()}
                    return AiText("""{"outcome":"ready","passageStart":0,"passageEnd":0,"takeaway":"Only current execution","quoteStart":null,"quoteEnd":null}""","model")
                }
            }
            val repo=CaptureRepository(db,gateway,{true},{},{},diagnostics=diagnostics)
            val id=repo.capture("ep",10000,true)
            val old=launch(dispatcher) {repo.run(id)}
            dispatcher.scheduler.runCurrent()
            withTimeout(10000) {while(db.library().distillation(id)!!.state!="running")delay(5)}
            val acquired=db.library().momentAttempt(db.library().distillation(id)!!.attempt)!!.run
            repo.cancel(id);repo.generate(id)
            val current=launch {repo.run(id)}
            withTimeout(10000) {entered.await()}
            val resumed=db.library().momentAttempt(db.library().distillation(id)!!.attempt)!!.run
            assertNotEquals(acquired,resumed)
            withTimeout(10000) {
                while(!old.isCompleted) {dispatcher.scheduler.runCurrent();delay(5)}
            }
            assertEquals(1,generations)
            assertEquals(resumed,diagnostics.journal.latest().single {it.kind=="distillation" && it.operation==id}.correlation!!.executionId)
            assertEquals("running",diagnostics.journal.latest().single {it.kind=="distillation" && it.operation==id}.state)
            release.complete(Unit);current.join()
            assertEquals("succeeded",db.library().distillation(id)!!.state)
        } finally {release.complete(Unit);dispatcher.scheduler.advanceUntilIdle();diagnostics.consent(false);db.close()}
    }
    private fun enabledDiagnostics():dev.distillpod.android.diagnostics.Diagnostics {
        val context=ApplicationProvider.getApplicationContext<Context>()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        return dev.distillpod.android.diagnostics.Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1").also {
            it.consent(false);it.consent(true)
        }
    }
    @Test fun `disabled diagnostics never adds provenance and later consent leaves capture incomplete`()=runBlocking {
        val db=database();val diagnostics=enabledDiagnostics()
        diagnostics.consent(false)
        try {
            seed(db)
            val repo=CaptureRepository(db,ai("unused"),{false},{},{},diagnostics=diagnostics)
            val id=repo.capture("ep",10000,true)
            val saved=db.library().momentAttempt(db.library().distillation(id)!!.attempt)!!
            assertFalse(JSONObject(saved.contextJson).has("_diagnostics"))
            diagnostics.consent(true)
            val c=repo.correlation(saved)
            assertNull(c.captureRequestId);assertNull(c.previousAttemptId)
            assertEquals("unknown",c.retryTrigger)
            assertEquals(false,c.fields()["correlation_complete"])
        } finally {diagnostics.consent(false);db.close()}
    }
    @Test fun `revoked provenance and in flight evidence cannot reappear after new consent`()=runBlocking {
        val db=database();val diagnostics=enabledDiagnostics()
        try {
            seed(db)
            val repo=CaptureRepository(db,ai("unused"),{false},{},{},diagnostics=diagnostics)
            val id=repo.capture("ep",10000,true)
            val saved=db.library().momentAttempt(db.library().distillation(id)!!.attempt)!!
            val old=repo.correlation(saved)
            assertNotNull(old.captureRequestId)
            assertFalse(old.fields().toString().contains(old.collectionEpoch!!))
            diagnostics.consent(false);diagnostics.consent(true)
            val fresh=repo.correlation(saved)
            assertNotEquals(old.collectionEpoch,fresh.collectionEpoch)
            assertNull(fresh.captureRequestId);assertNull(fresh.previousAttemptId)
            diagnostics.event("distillation.finished",id,reason="succeeded",correlation=old)
            diagnostics.awaitIdle()
            assertFalse(diagnostics.queue.files().any {it.readText().contains(old.captureRequestId!!)})
            assertFalse(diagnostics.journal.latest().any {it.operation==id})
        } finally {diagnostics.consent(false);db.close()}
    }
    /**
     * save() treats a same-position tap as a duplicate only within two wall-clock seconds of the saved row's
     * createdAt. Enabled diagnostics fsync the journal synchronously on this path (and contend for their lock
     * with the diagnostics executor), so a loaded host can exceed that window between taps (#192).
     * Pin the row inside the window so the duplicate-tap precondition holds.
     */
    private suspend fun keepInDuplicateWindow(db: AppDatabase, id: String) {
        val saved = db.library().distillation(id)!!
        db.library().putDistillation(saved.copy(createdAt = System.currentTimeMillis() + 60 * 60 * 1000L))
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
