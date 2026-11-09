package dev.distillpod.android.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.core.TimeRange
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
class ContextObservationTimeTest {
    @Test fun `original observation time survives emission and never defeats wait coalescing`() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("diagnostics",0).edit().clear().commit()
        File(context.noBackupFilesDir,"diagnostics").deleteRecursively()
        File(context.noBackupFilesDir,"reliability").deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val d=Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1")
        fun records()=d.queue.files().map {it.readText()}.filter {it.contains("capture.context_summary")}.also {payloads->
            val directory=File("build/test-context-time-fixtures").also {it.mkdirs()}
            payloads.forEachIndexed {index,payload->File(directory,"record-$index.jsonl").writeText(payload)}
        }.map {payload->
            payload.lineSequence().mapNotNull {runCatching {JSONObject(it)}.getOrNull()}
                .mapNotNull {it.optJSONArray("items")}.first().getJSONObject(0).getJSONObject("attributes")
        }
        try {
            d.consent(true);d.awaitIdle()
            val id=UUID.randomUUID().toString()
            val correlation=DiagnosticCorrelation(momentId=id,generationAttemptId=UUID.randomUUID().toString(),
                executionId=UUID.randomUUID().toString(),collectionEpoch=d.correlationEpoch)
            val evidence=ContextPreparationEvidence().apply {
                outcome="transcript_required";reason="coverage_missing"
                waitInput=ContextWaitInput.create(0,10,TimeRange(0,20),100,null,emptyList())
            }
            // A synthetic observation captured earlier must not be replaced by emission time.
            d.contextSummary(id,evidence,17,correlation,observedAt=1234L)
            d.contextSummary(id,evidence,99,correlation,observedAt=5678L)
            val wait=records().single()
            assertEquals(1234L,wait.getJSONObject("observed_at_ms").getLong("value"))
            assertNotEquals(1234L,wait.getJSONObject("emitted_at_ms").getLong("value"))
            assertEquals(17L,wait.getJSONObject("preparation_elapsed_ms").getLong("value"))
            evidence.outcome="queued";evidence.reason="ready"
            d.contextSummary(id,evidence,23,correlation,observedAt=6000L)
            val ready=records().single {it.getJSONObject("outcome").getString("value")=="queued"}
            assertEquals(6000L,ready.getJSONObject("observed_at_ms").getLong("value"))
            assertEquals(23L,ready.getJSONObject("preparation_elapsed_ms").getLong("value"))
            assertEquals(2,records().size)
            // A deliberately delayed synthetic observation can arrive after a newer one.
            evidence.outcome="failed";evidence.reason="internal"
            d.contextSummary(id,evidence,31,correlation,observedAt=1000L)
            val delayed=records().single {it.getJSONObject("outcome").getString("value")=="failed"}
            assertEquals(1000L,delayed.getJSONObject("observed_at_ms").getLong("value"))
            assertTrue(delayed.getJSONObject("emitted_at_ms").getLong("value")>=
                ready.getJSONObject("emitted_at_ms").getLong("value"))
            assertEquals(3,records().size)
        } finally {d.consent(false);io.sentry.Sentry.close()}
    }
}
