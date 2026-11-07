package dev.distillpod.android

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.ai.*
import dev.distillpod.android.data.*
import dev.distillpod.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class MomentQualityTest {
    private val accepted="""{"passageComplete":true,"takeawaySupported":true,"relevant":true,"quoteFaithful":true,"needsContext":false,"issues":[]}"""
    private val ready="""{"outcome":"ready","passageStart":0,"passageEnd":1,"takeaway":"The speaker rejects a fixed amount of work because lower costs can create additional demand.","quoteStart":0,"quoteEnd":0}"""
    private fun fixture(block:suspend(AppDatabase)->Unit)=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<android.content.Context>(),AppDatabase::class.java).build()
        try {
            db.library().putPodcast(PodcastEntity("p","https://example.test/feed","Synthetic show"))
            db.library().putEpisode(EpisodeEntity("e","p","g","Episode","https://example.test/audio",durationMs=200000,mediaFingerprint="sha"))
            db.library().putTranscript(TranscriptEntity("e","r","sha","tiny","en",state="ready",durationMs=200000))
            db.library().putCoverage(CoverageEntity("e","r",0,200000))
            db.library().putSegments(listOf(SegmentEntity("e","r",40000,60000,"There is a fixed amount of work. That is a mistaken belief: lower costs can create additional demand.")))
            block(db)
        } finally { db.close() }
    }
    private fun gateway(block:suspend(String,String)->String)=object:AiGateway {
        override suspend fun complete(instructions:String,input:String,onDelta:(String)->Unit)=AiText(block(instructions,input),"synthetic-model")
    }
    private fun repository(db:AppDatabase,ai:AiGateway,prepare:suspend(String)->Unit={})=CaptureRepository(db,ai,{true},prepare,{})

    @Test fun `verbatim rejected proposition is removed while a supported takeaway succeeds`()=fixture { db ->
        var calls=0
        val ai=gateway { prompt,_ -> calls++;if(prompt==MomentGeneration.generatePrompt)ready else
            """{"passageComplete":true,"takeawaySupported":true,"relevant":true,"quoteFaithful":false,"needsContext":false,"issues":[{"kind":"quote","spanIds":[0,1],"reason":"The quote endorses the proposition the speaker rejects."}]}""" }
        val repo=repository(db,ai);val id=repo.capture("e",60000,true);repo.run(id)
        val result=db.library().distillation(id)!!
        assertEquals("succeeded",result.state);assertNull(result.quote);assertTrue(result.insight!!.contains("rejects"));assertEquals(2,calls)
        assertEquals(40000L,result.sourceStartMs);assertEquals(0L,result.startMs);assertEquals(60000L,result.endMs)
        assertEquals(1,db.library().momentAttempts(id).size)
    }
    @Test fun `hypothesis turned into assertion is rejected rather than published`()=fixture { db ->
        db.library().deleteSegments("e")
        db.library().putSegments(listOf(SegmentEntity("e","r",40000,60000,"Let us suppose lenders stopped financing new factories. Construction could slow and suppliers might lose orders.")))
        val assertion="""{"outcome":"ready","passageStart":0,"passageEnd":1,"takeaway":"Lenders stopped financing factories and construction is slowing.","quoteStart":1,"quoteEnd":1}"""
        val ai=gateway { prompt,_ -> if(prompt==MomentGeneration.generatePrompt)assertion else
            """{"passageComplete":true,"takeawaySupported":false,"relevant":true,"quoteFaithful":false,"needsContext":false,"issues":[{"kind":"unsupported","spanIds":[0],"reason":"A hypothetical is stated as fact."},{"kind":"quote","spanIds":[0],"reason":"Missing conditional framing."}]}""" }
        val repo=repository(db,ai);val id=repo.capture("e",60000,true);repo.run(id)
        assertNull(db.library().distillation(id)!!.insight);assertEquals("unclear_source",db.library().distillation(id)!!.state)
    }
    @Test fun `interrupted review resumes candidate without a third generation call`()=fixture { db ->
        var generations=0;var reviews=0
        val ai=gateway { prompt,_ -> if(prompt==MomentGeneration.generatePrompt){generations++;ready} else {reviews++;if(reviews==1)throw QuotaExceeded();accepted} }
        val repo=repository(db,ai);val id=repo.capture("e",60000,true);repo.run(id)
        assertEquals("waiting_quota",db.library().distillation(id)!!.state)
        assertTrue(db.library().momentAttempts(id).single().candidateJson.isNotBlank())
        repo.generate(id);repo.run(id)
        assertEquals("succeeded",db.library().distillation(id)!!.state);assertEquals(1,generations);assertEquals(2,reviews)
    }
    @Test fun `missing future coverage waits and requests the context before generation`()=fixture { db ->
        db.library().deleteCoverage("e");db.library().putCoverage(CoverageEntity("e","r",0,60000))
        var prepared=0;var calls=0
        val repo=repository(db,gateway { _,_->calls++;ready }){prepared++}
        val id=repo.capture("e",60000,true);repo.run(id)
        assertEquals("waiting_transcript",db.library().distillation(id)!!.state);assertEquals(0,calls);assertEquals(1,prepared)
        db.library().putCoverage(CoverageEntity("e","r",60000,75000));repo.transcriptUpdated("e")
        assertEquals("queued",db.library().distillation(id)!!.state)
    }
    @Test fun `complete publisher transcript bounds capture and expansion when feed duration is missing`()=fixture { db ->
        val dao=db.library()
        dao.putEpisode(dao.episode("e")!!.copy(durationMs=0))
        dao.putTranscript(dao.transcript("e")!!.copy(source="publisher",model="publisher"))
        dao.deleteSegments("e")
        dao.putSegments(listOf(SegmentEntity("e","r",180000,200000,"There is a fixed amount of work. That is a mistaken belief: lower costs can create additional demand.")))
        var prepared=0;var calls=0
        val repo=repository(db,gateway { prompt,_ -> calls++;if(prompt==MomentGeneration.generatePrompt)ready else accepted }){prepared++}
        val id=repo.capture("e",195000,true)
        assertEquals("queued",dao.distillation(id)!!.state)
        repo.run(id)
        val result=dao.distillation(id)!!
        assertEquals("succeeded",result.state);assertEquals(200000L,result.sourceEndMs)
        assertEquals(135000L,result.startMs);assertEquals(195000L,result.endMs)
        repo.improve(id,true)
        val expanded=dao.distillation(id)!!
        val context=MomentGeneration.context(dao.momentAttempt(expanded.attempt)!!.contextJson)
        assertEquals("queued",expanded.state);assertEquals(TimeRange(15000,200000),context.range)
        repo.run(id)
        assertEquals("succeeded",dao.distillation(id)!!.state);assertEquals(0,prepared);assertEquals(4,calls)
    }
    @Test fun `unknown duration waits for partial transcript then resumes when publisher transcript is complete`()=fixture { db ->
        val dao=db.library()
        dao.putEpisode(dao.episode("e")!!.copy(durationMs=0))
        dao.putTranscript(dao.transcript("e")!!.copy(source="publisher",model="publisher",state="partial"))
        dao.deleteSegments("e")
        dao.putSegments(listOf(SegmentEntity("e","r",180000,200000,"There is a fixed amount of work. That is a mistaken belief: lower costs can create additional demand.")))
        var prepared=0;var calls=0
        val repo=repository(db,gateway { prompt,_ -> calls++;if(prompt==MomentGeneration.generatePrompt)ready else accepted }){prepared++}
        val id=repo.capture("e",195000,true);repo.run(id)
        assertEquals("waiting_transcript",dao.distillation(id)!!.state);assertEquals(1,prepared);assertEquals(0,calls)
        dao.putTranscript(dao.transcript("e")!!.copy(state="ready"))
        repo.transcriptUpdated("e")
        assertEquals("queued",dao.distillation(id)!!.state)
        repo.run(id)
        assertEquals("succeeded",dao.distillation(id)!!.state);assertEquals(1,prepared);assertEquals(2,calls)
    }
    @Test fun `failed improvement preserves published result and successful replacement has restorable history`()=fixture { db ->
        var reject=false
        val ai=gateway { prompt,_ -> if(prompt==MomentGeneration.generatePrompt){if(reject)"""{"outcome":"needs_context"}""" else ready} else accepted }
        val repo=repository(db,ai);val id=repo.capture("e",60000,true);repo.run(id)
        val original=db.library().distillation(id)!!
        reject=true;repo.improve(id);repo.run(id)
        assertEquals(original.insight,db.library().distillation(id)!!.insight)
        assertEquals(original.sourceJson,db.library().distillation(id)!!.sourceJson)
        reject=false;repo.improve(id,true);repo.run(id)
        val latest=db.library().distillation(id)!!;assertNotEquals(original.generationId,latest.generationId)
        val version=db.library().momentVersions(id).single();repo.restoreVersion(id,version.id)
        assertEquals(original.generationId,db.library().distillation(id)!!.generationId)
        assertEquals(2,db.library().momentVersions(id).size)
    }
    @Test fun `deleted moment during review is never resurrected`()=fixture { db ->
        var id=""
        val repo=repository(db,gateway { prompt,_ -> if(prompt==MomentGeneration.generatePrompt)ready else {db.library().deleteDistillation(id);accepted} })
        id=repo.capture("e",60000,true);repo.run(id)
        assertNull(db.library().distillation(id));assertTrue(db.library().momentAttempts(id).isEmpty())
    }
    @Test fun `source revision change during review cannot publish stale result`()=fixture { db ->
        val repo=repository(db,gateway { prompt,_ -> if(prompt==MomentGeneration.generatePrompt)ready else {
            db.library().putTranscript(db.library().transcript("e")!!.copy(revision="new"));accepted } })
        val id=repo.capture("e",60000,true);repo.run(id)
        assertNull(db.library().distillation(id)!!.insight);assertEquals("failed",db.library().distillation(id)!!.state)
    }
    @Test fun `cancellation during review preserves prior result and prevents publication`()=fixture { db ->
        var id=""
        lateinit var repo:CaptureRepository
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val diagnostics=dev.distillpod.android.diagnostics.Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1")
        diagnostics.consent(true)
        try {
            repo=CaptureRepository(db,gateway { prompt,_ -> if(prompt==MomentGeneration.generatePrompt)ready else {repo.cancel(id);accepted} },{true},{},{},diagnostics=diagnostics)
            id=repo.capture("e",60000,true);repo.run(id)
            assertNull(db.library().distillation(id)!!.insight);assertEquals("failed",db.library().distillation(id)!!.state)
            assertEquals("cancelled",diagnostics.journal.latest().single {it.kind=="distillation" && it.operation==id}.state)
        } finally {diagnostics.consent(false);io.sentry.Sentry.close()}
    }
    @Test fun `waiting context can be canceled without a later transcript update starting AI`()=fixture { db ->
        db.library().deleteCoverage("e")
        var calls=0
        val repo=repository(db,gateway { _,_->calls++;ready })
        val id=repo.capture("e",60000,true)
        repo.cancel(id)
        db.library().putCoverage(CoverageEntity("e","r",0,200000));repo.transcriptUpdated("e");repo.run(id)
        assertEquals("failed",db.library().distillation(id)!!.state);assertEquals(0,calls)
    }
    @Test fun `late review from canceled execution cannot publish into its retry`()=fixture { db ->
        var id="";lateinit var repo:CaptureRepository
        repo=repository(db,gateway { prompt,_ -> if(prompt==MomentGeneration.generatePrompt)ready else {
            repo.cancel(id);repo.generate(id)
            db.library().putDistillation(db.library().distillation(id)!!.copy(state="reviewing"))
            accepted
        } })
        id=repo.capture("e",60000,true);repo.run(id)
        assertNull(db.library().distillation(id)!!.insight)
        assertTrue(db.library().momentAttempts(id).single().reviewJson.isBlank())
        assertEquals("reviewing",db.library().distillation(id)!!.state)
    }
    @Test fun `content abstention makes one request and does not automatically retry`()=fixture { db ->
        var calls=0
        val repo=repository(db,gateway { _,_->calls++;"""{"outcome":"needs_context"}""" })
        val id=repo.capture("e",60000,true);repo.run(id);repo.run(id)
        assertEquals(1,calls);assertEquals("needs_context",db.library().distillation(id)!!.state)
    }
    @Test fun `review requires evidence and strict booleans`() {
        val context=MomentContexts.build(TimeRange(0,10000),TimeRange(0,25000),listOf(SubtitleSegment(TimeRange(0,9000),"A useful sentence.")))
        assertThrows(IllegalArgumentException::class.java) { MomentGeneration.review(accepted.replace("\"quoteFaithful\":true","\"quoteFaithful\":false"),context) }
        assertThrows(IllegalArgumentException::class.java) { MomentGeneration.review(accepted.replace("\"relevant\":true","\"relevant\":\"true\""),context) }
        assertThrows(IllegalArgumentException::class.java) { MomentGeneration.candidate(ready.replace("\"passageStart\":0","\"passageStart\":0.5"),context) }
    }
    @Test fun `takeaway only results survive backup and original capture remains preserved`()=fixture { db ->
        val repo=repository(db,gateway { prompt,_ -> if(prompt==MomentGeneration.generatePrompt)ready.replace("\"quoteStart\":0,\"quoteEnd\":0","\"quoteStart\":null,\"quoteEnd\":null") else accepted })
        val id=repo.capture("e",60000,true);repo.run(id);repo.improve(id);repo.run(id)
        val bytes=LibraryBackup(db).export()
        val other=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<android.content.Context>(),AppDatabase::class.java).build()
        try {
            val backup=LibraryBackup(other);backup.merge(backup.preview(bytes.inputStream()))
            val restored=other.library().distillation(id)!!
            assertNull(restored.quote);assertEquals("succeeded",restored.state);assertEquals(0L,restored.startMs)
            assertEquals(db.library().distillation(id)!!.sourceJson,restored.sourceJson)
            assertEquals(1,other.library().momentVersions(id).size);assertEquals(2,other.library().momentAttempts(id).size)
        } finally {other.close()}
    }
    @Test fun `posts use selected source timing and keep their snapshot through improvement`()=fixture { db ->
        val repo=repository(db,gateway { prompt,_ -> if(prompt==MomentGeneration.generatePrompt)ready else accepted })
        val id=repo.capture("e",60000,true);repo.run(id)
        val draft=PostRepository(db).create(id)
        val content=PostContent.parse(draft.content)
        assertEquals(40000L,content.startMs);assertEquals(60000L,content.endMs)
        repo.improve(id);repo.run(id)
        assertEquals(draft.content,db.library().post(draft.id)!!.content)
    }
    @Test fun `legacy explanation never adopts the source of an improved moment`()=fixture { db ->
        val repo=repository(db,gateway { prompt,_ -> if(prompt==MomentGeneration.generatePrompt)ready else accepted })
        val id=repo.capture("e",60000,true);repo.run(id)
        val legacy=ResearchEntity("legacy",id,state="succeeded",report="Old report",sourcesJson="""[{"startMs":0,"endMs":60000,"revision":"r"}]""")
        val current=db.library().distillation(id)!!
        assertFalse(explanationMatches(legacy,current))
        assertEquals("",explanationMoment(legacy,current).excerpt)
        assertEquals(0L,explanationMoment(legacy,current).sourceStartMs)
    }
    @Test fun `explanation keeps its original source snapshot after improvement`()=fixture { db ->
        val repo=repository(db,gateway { prompt,_ -> if(prompt==MomentGeneration.generatePrompt)ready else accepted })
        val id=repo.capture("e",60000,true);repo.run(id)
        val explanations=MomentExplanation(db,gateway { _,_->"The speaker argues that demand can expand as costs fall. This is grounded in the original saved passage." },{})
        val reportId=explanations.create(id)
        repo.improve(id);repo.run(id);explanations.run(reportId)
        val report=db.library().research(reportId)!!
        assertFalse(explanationMatches(report,db.library().distillation(id)!!))
        assertNotEquals(reportId,explanations.create(id))
    }
}
