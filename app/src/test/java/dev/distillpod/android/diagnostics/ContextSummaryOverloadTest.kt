package dev.distillpod.android.diagnostics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.android.ai.*
import dev.distillpod.android.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ContextSummaryOverloadTest {
    @Test fun `preparation flood bounds direct logs while retaining later terminal journal for rollover`()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val prefs=context.getSharedPreferences("diagnostics",0)
        prefs.edit().clear().commit()
        File(context.noBackupFilesDir,"diagnostics").deleteRecursively()
        File(context.noBackupFilesDir,"reliability").deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val d=Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1")
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val dao=db.library()
        val revision=UUID.randomUUID().toString()
        var calls=0
        var preparations=0
        val ai=object:AiGateway {
            override suspend fun complete(instructions:String,input:String,onDelta:(String)->Unit):AiText {
                calls++
                return AiText(if(instructions==MomentGeneration.reviewPrompt)
                    """{"passageComplete":true,"takeawaySupported":true,"relevant":true,"quoteFaithful":true,"needsContext":false,"issues":[]}"""
                else """{"outcome":"ready","passageStart":0,"passageEnd":0,"takeaway":"Synthetic thought remains supported by this passage.","quoteStart":0,"quoteEnd":0}""","gpt-5")
            }
        }
        fun payloads(name:String)=d.queue.files().map {it.readText()}.filter {it.contains(name)}
        fun healthFields(payload:String):JSONObject = payload.lineSequence()
            .mapNotNull {runCatching {JSONObject(it)}.getOrNull()}
            .mapNotNull {it.optJSONArray("items")}.first().getJSONObject(0).getJSONObject("attributes")
        try {
            dao.putPodcast(PodcastEntity("p","https://flood-private.invalid/feed","FLOOD_PRIVATE_TITLE"))
            dao.putEpisode(EpisodeEntity("e","p","g","FLOOD_PRIVATE_TITLE","https://flood-private.invalid/audio",
                durationMs=200000,mediaFingerprint="FLOOD_PRIVATE_HASH"))
            dao.putTranscript(TranscriptEntity("e",revision,"FLOOD_PRIVATE_HASH","tiny","en",state="ready",durationMs=200000))
            val repo=CaptureRepository(db,ai,{true},{preparations++},{},diagnostics=d)
            // Save before consent so the reserve is spent by real preparation results, not save events.
            val id=repo.save("e",60000,true)
            d.consent(true);d.awaitIdle()
            // The normal chunk callback rechecks all waiting moments, often with unchanged window coverage.
            repeat(165) {repo.transcriptUpdated("e");d.awaitIdle()}
            assertEquals(165,preparations)
            assertEquals(1,payloads("capture.context_summary").size)
            assertEquals(1,prefs.getInt("budget_critical",0))
            assertEquals(0L,prefs.getLong("critical_dropped",0))
            // Genuinely changing coverage must remain observable and can still reach the hard cap.
            repeat(164) { index ->
                dao.putCoverage(CoverageEntity("e",revision,0,index+1L))
                repo.transcriptUpdated("e");d.awaitIdle()
            }
            assertEquals(329,preparations)
            assertEquals(0,calls)
            assertEquals("waiting_transcript",dao.distillation(id)!!.state)
            assertEquals(160,payloads("capture.context_summary").size)
            assertEquals(160,prefs.getInt("budget_critical",0))
            assertEquals(5L,prefs.getLong("critical_dropped",0))
            assertTrue(payloads("capture.context_summary").all {it.contains("coverage_missing")})

            // A real later generation still completes, even though its direct outcome logs cannot fit.
            dao.putCoverage(CoverageEntity("e",revision,0,200000))
            dao.putSegments(listOf(SegmentEntity("e",revision,40000,60000,
                "Synthetic thought remains supported by this passage.")))
            repo.prepareSaved(id);d.awaitIdle()
            repo.run(id);d.awaitIdle()
            assertEquals("succeeded",dao.distillation(id)!!.state)
            assertEquals(2,calls)
            assertTrue(payloads("distillation.finished").isEmpty())
            assertTrue(payloads("distillation.attempt_summary").isEmpty())
            val terminal=d.journal.pending().single {it.kind=="distillation" && it.operation==id && it.state=="completed"}
            assertFalse(payloads("operation.summary").any {it.contains(terminal.id)})
            val drops=prefs.getLong("critical_dropped",0)
            assertTrue(drops>=8L)
            repeat(3) {d.flushSummaries()}
            assertEquals(drops,prefs.getLong("critical_dropped",0))
            assertTrue(d.journal.pending().any {it.id==terminal.id})
            assertTrue(prefs.getInt("sent",0)<=1000)
            assertTrue(prefs.getInt("budget_routine",0)<=800)
            assertEquals(160,prefs.getInt("budget_critical",0))
            assertTrue(prefs.getInt("budget_health",0) in 1..10)
            val health=payloads("diagnostics.health")
            assertTrue(health.size in 1..10)
            assertTrue(health.map(::healthFields).any {
                it.getJSONObject("critical_used").getInt("value")==160 &&
                    it.getJSONObject("critical_dropped").getLong("value")>0
            })
            // Aggregate drops describe admissions, not a count of missing attempts.
            assertEquals(1,dao.distillations().size)
            val beforeRollover=prefs.getInt("sent",0)
            assertTrue(beforeRollover>160)
            val contextLogs=payloads("capture.context_summary").size

            // Simulate the normal UTC rollover; the retained terminal row replays with its same ID.
            prefs.edit().putLong("budget_day",-1).commit()
            d.flushSummaries()
            assertEquals(1,payloads("operation.summary").count {it.contains(terminal.id)})
            assertFalse(d.journal.pending().any {it.id==terminal.id})
            val admitted=prefs.getInt("sent",0)
            repeat(3) {d.flushSummaries()}
            assertEquals(admitted,prefs.getInt("sent",0))
            assertEquals(1,payloads("operation.summary").count {it.contains(terminal.id)})
            assertEquals(contextLogs,payloads("capture.context_summary").size)
            assertTrue(payloads("distillation.finished").isEmpty())
            assertTrue(payloads("distillation.attempt_summary").isEmpty())
            assertTrue(admitted<=1000)
            val payload=d.queue.files().joinToString {it.readText()}
            listOf("FLOOD_PRIVATE","flood-private.invalid","Synthetic thought remains").forEach {
                assertFalse(payload.contains(it))
            }
        } finally {d.consent(false);assertTrue(d.queue.files().isEmpty());db.close();io.sentry.Sentry.close()}
    }
}
