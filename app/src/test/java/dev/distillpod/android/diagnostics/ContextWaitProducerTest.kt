package dev.distillpod.android.diagnostics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.android.ai.*
import dev.distillpod.android.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ContextWaitProducerTest {
    @Test fun `normal wait rechecks preserve changed identities reset failures and later terminal evidence`()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val prefs=context.getSharedPreferences("diagnostics",0)
        prefs.edit().clear().commit()
        val directory=File(context.noBackupFilesDir,"diagnostics")
        directory.deleteRecursively()
        File(context.noBackupFilesDir,"reliability").deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val d=Diagnostics(context,"https://abc123@o1.ingest.sentry.io/1")
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val dao=db.library()
        var revision=UUID.randomUUID().toString()
        var calls=0
        val ai=object:AiGateway {
            override suspend fun complete(instructions:String,input:String,onDelta:(String)->Unit):AiText {
                calls++
                return AiText("""{"outcome":"needs_context"}""","gpt-5")
            }
        }
        fun payloads(name:String)=d.queue.files().map {it.readText()}.filter {it.contains(name)}
        try {
            dao.putPodcast(PodcastEntity("p","https://example.test/feed","Synthetic"))
            dao.putEpisode(EpisodeEntity("e","p","g","Synthetic","https://example.test/audio",durationMs=200000,mediaFingerprint="synthetic"))
            dao.putTranscript(TranscriptEntity("e",revision,"synthetic","tiny","en",state="ready",durationMs=200000))
            val repo=CaptureRepository(db,ai,{true},{},{},diagnostics=d)
            val id=repo.save("e",60000,true)
            d.consent(true);d.awaitIdle()
            repeat(200) {repo.transcriptUpdated("e");d.awaitIdle()}
            assertEquals(1,payloads("capture.context_summary").size)
            assertEquals(1,prefs.getInt("budget_critical",0))
            assertEquals(0,calls)

            // Distinct durable executions and attempts cannot inherit suppression.
            var saved=dao.distillation(id)!!
            var attempt=dao.momentAttempt(saved.attempt)!!
            dao.putMomentAttempt(attempt.copy(run=UUID.randomUUID().toString()))
            repo.prepareSaved(id);d.awaitIdle()
            assertEquals(2,payloads("capture.context_summary").size)
            attempt=dao.momentAttempt(saved.attempt)!!.copy(id=UUID.randomUUID().toString())
            dao.putMomentAttempt(attempt);dao.putDistillation(saved.copy(attempt=attempt.id))
            repo.prepareSaved(id);d.awaitIdle()
            assertEquals(3,payloads("capture.context_summary").size)

            // Same missing duration but changed capture bounds still merits evidence.
            saved=dao.distillation(id)!!
            dao.putDistillation(saved.copy(startMs=saved.startMs+1))
            repo.prepareSaved(id);d.awaitIdle()
            assertEquals(4,payloads("capture.context_summary").size)
            revision=UUID.randomUUID().toString()
            dao.putTranscript(dao.transcript("e")!!.copy(revision=revision))
            repo.transcriptUpdated("e");d.awaitIdle()
            assertEquals(5,payloads("capture.context_summary").size)

            d.consent(false);assertTrue(d.queue.files().isEmpty())
            d.consent(true);d.awaitIdle()
            repo.prepareSaved(id);d.awaitIdle();repo.prepareSaved(id);d.awaitIdle()
            assertEquals(1,payloads("capture.context_summary").size)
            // Usage consent also clears the shared outbox, so cached insertion evidence must reset.
            d.usageConsent(false);d.awaitIdle()
            repo.prepareSaved(id);d.awaitIdle()
            assertEquals(1,payloads("capture.context_summary").size)

            // A changed wait whose outbox insertion fails must remain eligible after storage recovers.
            revision=UUID.randomUUID().toString()
            dao.putTranscript(dao.transcript("e")!!.copy(revision=revision))
            directory.deleteRecursively();directory.writeText("Synthetic unavailable outbox")
            try {repo.prepareSaved(id);d.awaitIdle()}
            finally {directory.delete();directory.mkdirs()}
            repo.prepareSaved(id);d.awaitIdle()
            assertEquals(1,payloads("capture.context_summary").size)
            repo.prepareSaved(id);d.awaitIdle()
            assertEquals(1,payloads("capture.context_summary").size)

            // Denied admission is not cached; it retries and becomes eligible after budget rollover.
            revision=UUID.randomUUID().toString()
            dao.putTranscript(dao.transcript("e")!!.copy(revision=revision))
            prefs.edit().putInt("budget_critical",160).commit()
            val dropped=prefs.getLong("critical_dropped",0)
            repeat(2) {repo.prepareSaved(id);d.awaitIdle()}
            assertEquals(dropped+2,prefs.getLong("critical_dropped",0))
            assertEquals(1,payloads("capture.context_summary").size)
            prefs.edit().putLong("budget_day",-1).commit()
            d.flushSummaries()
            repo.prepareSaved(id);d.awaitIdle()
            assertEquals(2,payloads("capture.context_summary").size)

            // Unchanged routine rechecks must leave critical admission for actual later outcomes.
            repeat(200) {repo.transcriptUpdated("e");d.awaitIdle()}
            dao.putCoverage(CoverageEntity("e",revision,0,200000))
            dao.putSegments(listOf(SegmentEntity("e",revision,40000,60000,"Synthetic source sentence.")))
            repo.prepareSaved(id);d.awaitIdle()
            assertEquals("queued",dao.distillation(id)!!.state)
            repo.run(id);d.awaitIdle()
            assertEquals("needs_context",dao.distillation(id)!!.state)
            assertEquals(1,calls)
            assertEquals(1,payloads("distillation.finished").size)
            assertEquals(1,payloads("distillation.attempt_summary").size)
            assertTrue(payloads("operation.summary").any {it.contains(id) && it.contains("failed")})
            assertTrue(prefs.getInt("budget_critical",0)<160)
        } finally {d.consent(false);db.close();io.sentry.Sentry.close()}
    }
}
