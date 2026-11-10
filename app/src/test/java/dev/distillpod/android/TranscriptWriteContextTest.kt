package dev.distillpod.android

import android.database.sqlite.SQLiteConstraintException
import dev.distillpod.android.data.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptWriteContextTest {
    @Test fun constraintEvidencePreservesOriginalFailureAndHasOnlyConstantContext() = runBlocking<Unit> {
        val original = SQLiteConstraintException("Synthetic original failure")
        val prior = IllegalStateException("Synthetic original suppressed evidence")
        original.addSuppressed(prior)
        val stack = original.stackTrace.toList()
        val thrown = writeFailure(original)
        assertSame(original, thrown)
        assertEquals(stack, thrown.stackTrace.toList())
        assertNull(thrown.cause)
        assertSame(prior, thrown.suppressed.first())
        assertEquals(2, thrown.suppressed.size)
        val marker = thrown.suppressed.last()
        assertEquals("transcript.write.site:prepare_new", marker.message)
        assertTrue(marker.stackTrace.isEmpty())
        assertNull(marker.cause)
        assertTrue(marker.suppressed.isEmpty())
        assertFalse(marker.toString().contains("PRIVATE_ROW_SENTINEL"))
    }

    @Test fun cancellationRetainsIdentityWithoutConstraintEvidence() = runBlocking<Unit> {
        val original = CancellationException("Synthetic cancellation")
        assertSame(original, writeFailure(original))
        assertTrue(original.suppressed.isEmpty())
    }

    @Test fun unrelatedFailuresRetainIdentityWithoutConstraintEvidence() = runBlocking<Unit> {
        val original = IllegalArgumentException("Synthetic unrelated failure")
        assertSame(original, writeFailure(original))
        assertTrue(original.suppressed.isEmpty())
    }

    private suspend fun writeFailure(original: Exception): Throwable {
        val dao = Proxy.newProxyInstance(LibraryDao::class.java.classLoader, arrayOf(LibraryDao::class.java)) { _, method, _ ->
            check(method.name == "putTranscript") { "Unexpected DAO operation" }
            throw original
        } as LibraryDao
        val row = TranscriptEntity("PRIVATE_ROW_SENTINEL", "private-revision", "private-fingerprint", "private-model", "en")
        return runCatching { dao.putTranscriptAt(row, TranscriptWriteSite.PREPARE_NEW) }.exceptionOrNull()
            ?: error("The original failure must propagate")
    }
}
