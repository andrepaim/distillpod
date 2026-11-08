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
class CaptureContextEvidenceTest {
    private val secret="Private sentence. Another private sentence."
    private fun scenario(kind:String):Map<String,Any> = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("diagnostics",0).edit().clear().commit()
        File(context.noBackupFilesDir,"diagnostics").deleteRecursively()
        File(context.noBackupFilesDir,"reliability").deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val diagnostics=Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1")
        diagnostics.contextSummary(UUID.randomUUID().toString(),ContextPreparationEvidence(),0,null)
        assertTrue(diagnostics.queue.files().isEmpty())
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val dao=db.library()
        val revision=UUID.randomUUID().toString()
        try {
            dao.putPodcast(PodcastEntity("pod","https://private.invalid/feed","Private title"))
            dao.putEpisode(EpisodeEntity("ep","pod","guid","Private title","https://private.invalid/audio",
                durationMs=if(kind=="unknown")0 else 300000,mediaFingerprint="private-content-hash"))
            dao.putTranscript(TranscriptEntity("ep",revision,"private-content-hash","tiny","en",
                state="ready",durationMs=if(kind=="unknown")0 else 300000,processedMs=300000))
            if(kind !in setOf("missing","cancelled","exception"))dao.putCoverage(CoverageEntity("ep",revision,0,if(kind=="partial")100000 else 300000))
            val segments=when(kind) {
                "empty" -> listOf(SegmentEntity("ep",revision,140000,200000,"  "))
                "crossing" -> listOf(SegmentEntity("ep",revision,70000,150000,"left private"),
                    SegmentEntity("ep",revision,190000,220000,"right private"))
                "oversize" -> listOf(SegmentEntity("ep",revision,140000,200000,"x".repeat(24000)))
                else -> listOf(SegmentEntity("ep",revision,140000,200000,secret))
            }
            dao.putSegments(segments)
            var calls=0
            val ai=object:AiGateway {
                override suspend fun complete(instructions:String,input:String,onDelta:(String)->Unit):AiText {
                    calls++;error("No request allowed")
                }
            }
            val repo=CaptureRepository(db,ai,{
                if(kind=="revision")runBlocking {dao.putTranscript(dao.transcript("ep")!!.copy(revision=UUID.randomUUID().toString()))}
                true
            },{
                if(kind=="cancelled")throw kotlinx.coroutines.CancellationException(secret)
                if(kind=="exception")throw IllegalStateException(secret)
            }, {},diagnostics=diagnostics)
            val id=repo.save("ep",200000,kind!="source")
            if(kind=="invalid")dao.putDistillation(dao.distillation(id)!!.copy(startMs=-1))
            if(kind=="invalid_range")dao.putDistillation(dao.distillation(id)!!.copy(endMs=-1))
            diagnostics.consent(true);diagnostics.awaitIdle()
            if(kind=="budget")context.getSharedPreferences("diagnostics",0).edit()
                .putInt("budget_routine",800).putInt("sent",800).commit()
            if(kind in setOf("invalid_range","cancelled","exception")) {
                try {repo.prepareSaved(id);fail("Expected preparation failure")}catch(_:Exception) {}
            } else repo.prepareSaved(id)
            diagnostics.awaitIdle()
            val payloads=diagnostics.queue.files().map {it.readText()}.filter {it.contains("capture.context_summary")}
            assertEquals(1,payloads.size)
            val payload=payloads.single()
            // Synthetic, content-free producer envelopes also exercise the offline report consumer.
            File("build/test-context-fixtures").mkdirs()
            File("build/test-context-fixtures/"+kind+".jsonl").writeText(payload)
            listOf(secret,"private.invalid","Private title","private-content-hash","left private","right private").forEach {
                assertFalse("Leaked content: "+it,payload.contains(it))
            }
            val item=payload.lineSequence().mapNotNull {runCatching {JSONObject(it)}.getOrNull()}
                .mapNotNull {it.optJSONArray("items")}.first().getJSONObject(0)
            val attributes=item.getJSONObject("attributes")
            val fields=attributes.keys().asSequence().associateWith {attributes.getJSONObject(it).get("value")}
            assertEquals(0,calls)
            if(fields["frozen_context"]==true) {
                val saved=dao.distillation(id)!!
                val frozen=MomentGeneration.context(dao.momentAttempt(saved.attempt)!!.contextJson)
                assertEquals(frozen.spans.size,(fields["usable_span_count"] as Number).toInt())
                assertEquals(frozen.spans.sumOf {it.text.length+1},(fields["context_text_units"] as Number).toInt())
            }
            fields
        } finally {diagnostics.consent(false);db.close()}
    }
    @Test fun `cancelled and exceptional exits redact messages and routine exhaustion preserves summary`() {
        assertEquals("cancelled",scenario("cancelled")["context_reason"])
        assertEquals("internal",scenario("exception")["context_reason"])
        assertEquals("ready",scenario("budget")["context_reason"])
    }
    @Test fun `missing and partial source coverage are truthful waits`() {
        val missing=scenario("missing")
        assertEquals("transcript_required",missing["outcome"])
        assertEquals(135000,(missing["missing_duration_ms"] as Number).toInt())
        assertEquals(false,missing["segments_measured"])
        val partial=scenario("partial")
        assertEquals(115000,(partial["missing_duration_ms"] as Number).toInt())
        assertEquals(false,partial["coverage_complete"])
    }
    @Test fun `complete empty speech and crossing segments are separate causes`() {
        val empty=scenario("empty")
        assertEquals(true,empty["coverage_complete"])
        assertEquals("empty_speech",empty["context_reason"])
        val crossing=scenario("crossing")
        assertEquals("empty_context",crossing["context_reason"])
        assertEquals(1,(crossing["excluded_left_count"] as Number).toInt())
        assertEquals(1,(crossing["excluded_right_count"] as Number).toInt())
        assertEquals(0,(crossing["usable_span_count"] as Number).toInt())
    }
    @Test fun `oversized and invalid contexts emit exactly one result`() {
        assertEquals("oversized",scenario("oversize")["context_reason"])
        assertEquals("invalid_bounds",scenario("invalid")["context_reason"])
        assertEquals("invalid_bounds",scenario("invalid_range")["context_reason"])
    }
    @Test fun `source and revision changes have distinct evidence`() {
        assertEquals("source_mismatch",scenario("source")["context_reason"])
        assertEquals("revision_mismatch",scenario("revision")["context_reason"])
    }
    @Test fun `successful frozen context and unknown duration do not alter selection`() {
        val ready=scenario("ready")
        assertEquals("queued",ready["outcome"])
        assertEquals(true,ready["frozen_context"])
        assertEquals(true,ready["coverage_complete"])
        assertEquals(false,scenario("unknown")["duration_known"])
    }
}
