package dev.distillpod.android.diagnostics

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.diagnostics.DiagnosticDelivery.Outcome
import dev.distillpod.android.diagnostics.DiagnosticDelivery.Receipt
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** #300: one slow or failing upload must not stall delivery for hours. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class DiagnosticUploaderTest {
    @get:Rule val temporary=TemporaryFolder()
    private lateinit var prefs:SharedPreferences
    private lateinit var directory:File
    private var clock=0L
    private lateinit var queue:DiagnosticQueue
    private lateinit var state:DiagnosticUploadState
    private val sent=mutableListOf<String>()

    @Before fun setup() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        prefs=context.getSharedPreferences("upload-test",0).also {it.edit().clear().commit()}
        directory=temporary.newFolder()
        clock=System.currentTimeMillis()
        queue=DiagnosticQueue(directory,now={clock})
        state=DiagnosticUploadState(prefs,now={clock})
    }
    /** Files keep their real modification time unless [ageMs] backdates them. */
    private fun append(text:String,priority:Boolean=false,ageMs:Long=0) {
        clock++
        val before=if(ageMs>0)directory.list().orEmpty().toSet() else emptySet()
        assertTrue(queue.append(text.toByteArray(),priority))
        if(ageMs>0)assertTrue(directory.listFiles()!!.single {it.name !in before}.setLastModified(clock-ageMs))
    }
    private fun uploader(send:(String)->Receipt)=DiagnosticUploader(queue,state,{bytes->String(bytes).also {sent+=it}.let(send)},now={clock})

    @Test fun `a saturated outbox drains in one run instead of a hundred envelopes per backed-off retry`() {
        repeat(1000) {append("routine-$it")}
        val next=uploader {Receipt(Outcome.ACCEPTED,code=200)}.drain {true}
        assertEquals(DiagnosticUploader.Next.Done,next)
        assertEquals(1000,sent.size)
        assertTrue(queue.files().isEmpty())
        assertEquals(0,state.consecutiveFailures)
        assertEquals(clock,state.lastUpload)
    }

    @Test fun `a run that reaches its time budget continues promptly with no failure recorded`() {
        repeat(20) {append("routine-$it")}
        val next=uploader {clock+=DiagnosticUploader.RUN_BUDGET_MS/5;Receipt(Outcome.ACCEPTED,code=200)}.drain {true}
        assertEquals(DiagnosticUploader.Next.Continue(DiagnosticUploader.CONTINUE_DELAY_MS),next)
        assertTrue(sent.size in 5..6)
        assertEquals(0,state.consecutiveFailures)
    }

    @Test fun `priority reports upload before an older routine backlog`() {
        append("old-routine");append("newer-routine");append("health",priority=true)
        uploader {Receipt(Outcome.ACCEPTED,code=200)}.drain {true}
        assertEquals(listOf("health","old-routine","newer-routine"),sent)
    }

    @Test fun `one envelope that keeps failing does not block later ones and is discarded after repeated isolated failures`() {
        append("poisoned");repeat(3) {append("later-$it")}
        val send={text:String->if(text=="poisoned")Receipt(Outcome.RETRY,code=500) else Receipt(Outcome.ACCEPTED,code=200)}
        assertEquals(DiagnosticUploader.Next.Continue(DiagnosticUploader.CONTINUE_DELAY_MS),uploader(send).drain {true})
        assertEquals(listOf("poisoned"),queue.files().map {it.readText()})
        assertEquals(0,state.consecutiveFailures)
        repeat(2) {append("more-$it");uploader(send).drain {true}}
        assertTrue(queue.files().isEmpty())
        assertEquals(1L,state.fields(queue.files(),clock)["upload_discarded"])
        assertEquals(listOf("poisoned","later-0","later-1","later-2","poisoned","more-0","poisoned","more-1"),sent)
    }

    @Test fun `a failure shared by consecutive envelopes stops the run without blaming either one`() {
        repeat(5) {append("routine-$it")}
        repeat(4) {
            val next=uploader {throw IOException("offline")}.drain {true}
            assertTrue(next is DiagnosticUploader.Next.Continue)
        }
        assertEquals(8,sent.size)
        assertEquals(5,queue.files().size)
        assertEquals(8,state.consecutiveFailures)
        assertEquals(0L,state.fields(queue.files(),clock)["upload_discarded"])
    }

    @Test fun `the retry delay grows but stays bounded however long failures last`() {
        append("routine")
        val delays=List(40) {
            clock+=60_000
            (uploader {Receipt(Outcome.RETRY,code=503)}.drain {true} as DiagnosticUploader.Next.Continue).delayMs
        }
        assertEquals(delays.sorted(),delays)
        assertTrue(delays.first()<=60_000)
        assertEquals(DiagnosticUploadState.MAX_RETRY_DELAY_MS,delays.last())
        assertTrue(delays.all {it<=DiagnosticUploadState.MAX_RETRY_DELAY_MS})
    }

    @Test fun `an authorization failure stops at once without trying or blaming other envelopes`() {
        append("first");append("second")
        val next=uploader {Receipt(Outcome.RETRY,code=401)}.drain {true}
        assertTrue(next is DiagnosticUploader.Next.Continue)
        assertEquals(listOf("first"),sent)
        assertEquals(1,state.consecutiveFailures)
    }

    @Test fun `a rate limit pauses without attempting and is capped at an hour`() {
        append("first");append("second")
        val limited=uploader {Receipt(Outcome.RETRY,retrySeconds=86_400,code=429)}.drain {true}
        assertEquals(DiagnosticUploader.Next.Continue(DiagnosticUploadState.MAX_PAUSE_MS),limited)
        assertEquals(listOf("first"),sent)
        clock+=10*60_000
        val waiting=uploader {fail("no upload while paused");Receipt(Outcome.ACCEPTED)}.drain {true}
        assertEquals(DiagnosticUploader.Next.Continue(DiagnosticUploadState.MAX_PAUSE_MS-10*60_000),waiting)
        clock+=DiagnosticUploadState.MAX_PAUSE_MS
        assertEquals(DiagnosticUploader.Next.Done,uploader {Receipt(Outcome.ACCEPTED,code=200)}.drain {true})
        assertEquals(listOf("first","first","second"),sent)
    }

    @Test fun `a pause stored by an older version is clamped to the cap`() {
        append("first")
        prefs.edit().putLong("retry_after",clock+86_400_000).commit()
        val next=uploader {Receipt(Outcome.ACCEPTED,code=200)}.drain {true}
        assertEquals(DiagnosticUploader.Next.Continue(DiagnosticUploadState.MAX_PAUSE_MS),next)
    }

    @Test fun `an accepted response that carries a rate limit still deletes the envelope and pauses`() {
        append("first");append("second")
        val next=uploader {Receipt(Outcome.ACCEPTED,retrySeconds=60,code=200)}.drain {true}
        assertEquals(DiagnosticUploader.Next.Continue(60_000),next)
        assertEquals(listOf("second"),queue.files().map {it.readText()})
    }

    @Test fun `rejected envelopes are discarded and counted without stopping delivery`() {
        append("invalid");append("valid")
        assertEquals(DiagnosticUploader.Next.Done,uploader {if(it=="invalid")Receipt(Outcome.REJECTED,code=400) else Receipt(Outcome.ACCEPTED,code=200)}.drain {true})
        assertEquals(1L,state.fields(queue.files(),clock)["upload_rejected"])
    }

    @Test fun `a stopped worker leaves the remaining envelopes for the next run`() {
        repeat(3) {append("routine-$it")}
        var active=true
        val next=uploader {active=false;Receipt(Outcome.ACCEPTED,code=200)}.drain {active}
        assertEquals(DiagnosticUploader.Next.Stopped,next)
        assertEquals(2,queue.files().size)
    }

    @Test fun `a stall is reported once when delivery resumes`() {
        append("waited",ageMs=5*60*60_000L)
        repeat(3) {uploader {throw IOException("offline")}.drain {true};clock+=60_000}
        append("fresh")
        assertNull(state.pendingStallReport())
        assertEquals(DiagnosticUploader.Next.Done,uploader {Receipt(Outcome.ACCEPTED,code=200)}.drain {true})
        val report=state.pendingStallReport()!!
        assertTrue(report["upload_stall_wait_ms"] as Long>=5*60*60_000L)
        assertEquals(3,report["upload_stall_failures"])
        assertEquals(2,report["upload_stall_pending"])
        state.stallReported()
        assertNull(state.pendingStallReport())
        append("next");uploader {Receipt(Outcome.ACCEPTED,code=200)}.drain {true}
        assertNull(state.pendingStallReport())
    }

    @Test fun `health fields describe delivery with numbers only`() {
        append("old",ageMs=2*60*60_000L)
        uploader {throw IOException("offline")}.drain {true}
        val fields=state.fields(queue.files(),clock)
        assertTrue(fields["oldest_pending_age_ms"] as Long>=2*60*60_000L)
        assertEquals(1,fields["upload_failures"])
        assertEquals(-1L,fields["last_upload_age_ms"])
        assertTrue(fields.values.all {it is Number})
        assertTrue(state.summary(queue.files(),clock).contains("1 failed upload attempt"))
    }
}
