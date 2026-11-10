package dev.distillpod.android

import dev.distillpod.android.features.runAppAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AppActionTest {
    @Test fun unexpectedErrorsAreReportedUnchangedAndCancellationPropagates() = runBlocking {
        val reported = mutableListOf<Exception>()
        val messages = mutableListOf<String>()
        for (error in listOf(IllegalArgumentException("Invalid audio path"), IOException("disk failure"), IllegalStateException("unexpected"))) {
            runAppAction({ throw error }, reported::add, { messages.add(it) })
            assertSame(error, reported.last())
        }
        assertEquals(3, reported.size)
        assertEquals(3, messages.size)
        val cancelled = CancellationException("cancelled")
        assertSame(cancelled, runCatching {
            runAppAction({ throw cancelled }, reported::add, { messages.add(it) })
        }.exceptionOrNull())
        assertEquals(3, reported.size)
        runAppAction({}, reported::add, { messages.add(it) })
        assertEquals(3, messages.size)
    }
}
