package dev.distillpod.android.diagnostics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.android.*
import dev.distillpod.android.ai.*
import dev.distillpod.android.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class DistillationSummaryTest {
    private val ready="""{"outcome":"ready","passageStart":0,"passageEnd":0,"takeaway":"Synthetic thought remains supported by this passage.","quoteStart":0,"quoteEnd":0}"""
    private val accepted="""{"passageComplete":true,"takeawaySupported":true,"relevant":true,"quoteFaithful":true,"needsContext":false,"issues":[]}"""
    private fun fixture(answer:suspend(Int)->String, model:String="PRIVATE-MODEL", test:suspend(CaptureRepository,AppDatabase,Diagnostics,String)->Unit)=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("diagnostics",0).edit().clear().commit()
        java.io.File(context.noBackupFilesDir,"diagnostics").deleteRecursively()
        java.io.File(context.noBackupFilesDir,"reliability").deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val d=Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1")
        d.consent(true)
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            val dao=db.library()
            val revision=java.util.UUID.randomUUID().toString()
            dao.putPodcast(PodcastEntity("p","https://example.test/feed","Synthetic"))
            dao.putEpisode(EpisodeEntity("e","p","g","Synthetic","https://example.test/audio",durationMs=200000,mediaFingerprint="sha"))
            dao.putTranscript(TranscriptEntity("e",revision,"sha","tiny","en",state="ready",durationMs=200000))
            dao.putCoverage(CoverageEntity("e",revision,0,200000))
            dao.putSegments(listOf(SegmentEntity("e",revision,40000,60000,"Synthetic thought remains supported by this passage.")))
            var calls=0
            val ai=object:AiGateway {
                override suspend fun complete(instructions:String,input:String,onDelta:(String)->Unit)=AiText(answer(++calls),model)
            }
            val repo=CaptureRepository(db,ai,{true},{},{},diagnostics=d)
            val id=repo.capture("e",60000,true)
            test(repo,db,d,id)
        } finally {d.consent(false);db.close();io.sentry.Sentry.close()}
    }
    private fun summaries(d:Diagnostics):List<String> {
        d.awaitIdle()
        return d.queue.files().map {it.readText()}.filter {it.contains("distillation.attempt_summary")}.also {rows->
            // Explicit opt-in export for offline consumer contract tests. These fixtures use only synthetic data.
            System.getenv("DISTILLPOD_SYNTHETIC_SUMMARY_DIR")?.takeIf {it.isNotBlank()}?.let {path->
                val directory=java.io.File(path).also {it.mkdirs()}
                rows.forEach {row->
                    val attributes=fields(row)
                    java.io.File(directory,"${attributes.getValue("stage_outcome")}-${attributes.getValue("summary_id")}.jsonl").writeText(row)
                }
            }
        }
    }
    private fun fields(payload:String):Map<String,Any> {
        fun find(value:Any):org.json.JSONObject? {
            if(value is org.json.JSONObject) {
                if(value.has("outcome_version"))return value
                for(key in value.keys())find(value.get(key))?.let {return it}
            }
            if(value is org.json.JSONArray)for(i in 0 until value.length())find(value.get(i))?.let {return it}
            return null
        }
        val attributes=payload.lineSequence().mapNotNull {runCatching {find(org.json.JSONObject(it))}.getOrNull()}.first()
        return attributes.keys().asSequence().associateWith {attributes.getJSONObject(it).get("value")}
    }
    @Test fun `review resume has new execution identity and counts only new attempted requests`()=fixture({
        when(it) {1->ready;2->throw QuotaExceeded();else->accepted}
    },model="gpt-5") {repo,_,d,id->
        repo.run(id)
        val first=fields(summaries(d).single())
        assertEquals(1,(first["generation_attempted_calls"] as Number).toInt())
        assertEquals(1,(first["review_attempted_calls"] as Number).toInt())
        assertEquals("review",first["stage"]);assertEquals("quota",first["stage_outcome"])
        assertEquals(first["execution_id"],first["summary_id"])
        assertEquals(true,first["correlation_complete"])
        assertDiagnosticWallTimes(first)
        repo.generate(id);repo.run(id)
        val second=summaries(d).map {fields(it)}.single {it["execution_id"]!=first["execution_id"]}
        assertEquals(first["generation_attempt_id"],second["generation_attempt_id"])
        assertEquals(0,(second["generation_attempted_calls"] as Number).toInt())
        assertEquals(-1L,(second["generation_elapsed_ms"] as Number).toLong())
        assertEquals(1,(second["review_attempted_calls"] as Number).toInt())
        assertEquals("cached",second["generation_outcome"])
        assertEquals("gpt-5",second["generator_model"]);assertEquals("gpt-5",second["reviewer_model"])
        assertEquals("published",second["stage_outcome"])
        assertEquals(true,second["candidate_reused"])
    }
    @Test fun `queue storage failure cannot prevent publication`()=fixture({if(it==1)ready else accepted}) {repo,db,d,id->
        d.awaitIdle()
        val directory=java.io.File(ApplicationProvider.getApplicationContext<Context>().noBackupFilesDir,"diagnostics")
        directory.deleteRecursively();directory.writeText("Synthetic unavailable outbox")
        try {repo.run(id);assertEquals("succeeded",db.library().distillation(id)!!.state)}
        finally {directory.delete();directory.mkdirs()}
    }
    @Test fun `generator abstentions are distinct and private values never serialize`() {
        for(outcome in listOf("needs_context","unclear_source"))fixture({"""{"outcome":"$outcome"}"""}) {repo,_,d,id->
            repo.run(id)
            val payload=summaries(d).single()
            assertTrue(payload.contains("generator_$outcome"))
            assertTrue(payload.contains("not_run"))
            assertFalse(payload.contains("PRIVATE-MODEL"))
            assertFalse(payload.contains("Synthetic thought"))
        }
    }
    @Test fun `review rejection and quote omission retain distinct outcomes`() {
        for(kind in listOf("incomplete","unsupported","irrelevant","quote")) {
            val flag=mapOf("incomplete" to "passageComplete","unsupported" to "takeawaySupported","irrelevant" to "relevant","quote" to "quoteFaithful").getValue(kind)
            val review=org.json.JSONObject(accepted).put(flag,false).put("issues",org.json.JSONArray().put(org.json.JSONObject().put("kind",kind).put("spanIds",org.json.JSONArray().put(0)).put("reason","PRIVATE-REVIEW"))).toString()
            fixture({if(it==1)ready else review}) {repo,db,d,id->
                repo.run(id)
                val payload=summaries(d).single()
                assertTrue(payload.contains(if(kind=="quote")"published_without_quote" else "review_rejected"))
                assertTrue(payload.contains("issue_${kind}_count"))
                assertFalse(payload.contains("PRIVATE-REVIEW"))
                assertEquals(kind=="quote",db.library().distillation(id)!!.state=="succeeded")
            }
        }
    }
    @Test fun `malformed generation and failed review have truthful stages`() {
        fixture({"PRIVATE-INVALID"}) {repo,_,d,id->
            repo.run(id);val payload=summaries(d).single()
            assertTrue(payload.contains("invalid_response"));assertTrue(payload.contains("generation"))
            assertFalse(payload.contains("PRIVATE-INVALID"))
        }
        fixture({if(it==1)ready else throw QuotaExceeded()}) {repo,_,d,id->
            repo.run(id);val payload=summaries(d).single()
            assertTrue(payload.contains("quota"));assertTrue(payload.contains("review"))
        }
    }
    @Test fun `coroutine interruption emits once and rethrows`()=fixture({throw kotlinx.coroutines.CancellationException("PRIVATE")}) {repo,_,d,id->
        try {repo.run(id);fail("must propagate cancellation")}catch(_:kotlinx.coroutines.CancellationException){}
        assertTrue(summaries(d).single().contains("interrupted"))
    }
    @Test fun `contradictory review is observed without changing rejection`() {
        val review=org.json.JSONObject(accepted).put("issues",org.json.JSONArray().put(org.json.JSONObject().put("kind","unsupported").put("spanIds",org.json.JSONArray().put(0)).put("reason","PRIVATE-REASON"))).toString()
        fixture({if(it==1)ready else review}) {repo,db,d,id->
            repo.run(id)
            val payload=summaries(d).single()
            assertEquals(true,fields(payload)["contradictory_review"])
            assertEquals(1,(fields(payload)["issue_unsupported_count"] as Number).toInt())
            assertTrue(payload.contains("review_rejected"))
            assertEquals("unclear_source",db.library().distillation(id)!!.state)
        }
    }
    @Test fun `late result after explicit cancellation is suppressed with one summary`() {
        var interrupt:suspend()->Unit={}
        fixture({interrupt();ready}) {repo,db,d,id->
            interrupt={repo.cancel(id)}
            repo.run(id)
            val payload=summaries(d).single()
            assertTrue(payload.contains("stale_result_suppressed"))
            assertFalse(payload.contains("published"))
            assertNull(db.library().distillation(id)!!.insight)
            assertTrue(d.queue.files().any {it.readText().contains("distillation.cancelled")})
        }
    }
    @Test fun `malformed review preserves generator success and records review invalidity`()=fixture({if(it==1)ready else "{}"}) {repo,_,d,id->
        repo.run(id)
        val payload=summaries(d).single()
        assertTrue(payload.contains("invalid_response"))
        assertTrue(payload.contains("generation_outcome"))
        assertTrue(payload.contains("review_outcome"))
    }
    @Test fun `isolated instrumentation never emits an attempt summary`()=fixture({if(it==1)ready else accepted}) {repo,_,d,id->
        dev.distillpod.android.sync.SubscriptionCloudAccess.instrumentation=true
        try {repo.run(id);assertTrue(summaries(d).isEmpty())}
        finally {dev.distillpod.android.sync.SubscriptionCloudAccess.instrumentation=false}
    }
    @Test fun `non IO gateway failures are request failures rather than parser failures`()=fixture({throw IllegalStateException("PRIVATE")}) {repo,_,d,id->
        repo.run(id);assertTrue(summaries(d).single().contains("request_failed"))
    }
    @Test fun `obsolete transcript never reports a provider failure`() {
        var replace:suspend()->Unit={}
        fixture({replace();ready}) {repo,db,d,id->
            replace={val old=db.library().transcript("e")!!;db.library().putTranscript(old.copy(revision="new-revision"))}
            repo.run(id)
            assertTrue(summaries(d).single().contains("stale_result_suppressed"))
            assertNull(db.library().distillation(id)!!.insight)
        }
    }
    @Test fun `revocation and reenable cannot resurrect an in flight attempt summary`() {
        var revoke:suspend()->Unit={}
        fixture({if(it==1){revoke();ready}else accepted}) {repo,db,d,id->
            revoke={d.consent(false);d.consent(true)}
            repo.run(id)
            assertEquals("succeeded",db.library().distillation(id)!!.state)
            assertTrue(summaries(d).isEmpty())
        }
    }
    @Test fun `failed ownership storage check is not mislabeled as a stale provider result`() {
        var breakStorage:()->Unit={}
        fixture({breakStorage();ready}) {repo,db,d,id->
            // Closing Room reopens a fresh in-memory database, which is legitimately stale.
            // Remove this synthetic fixture's table to make both ownership queries actually fail.
            breakStorage={db.openHelper.writableDatabase.execSQL("DROP TABLE distillations")}
            runCatching {repo.run(id)}
            assertEquals("internal_failure",fields(summaries(d).single())["stage_outcome"])
        }
    }
    @Test fun `consent off and revocation prevent stage envelopes`()=fixture({ready}) {repo,_,d,id->
        d.consent(false);repo.run(id);assertTrue(summaries(d).isEmpty())
    }
}
