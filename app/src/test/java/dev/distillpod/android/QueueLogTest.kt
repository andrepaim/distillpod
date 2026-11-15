package dev.distillpod.android

import dev.distillpod.android.playback.QueueLog
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueLogTest {
    @Test fun `ids are bounded with the remaining count`() {
        assertEquals("[]", QueueLog.ids(emptyList()))
        assertEquals("[a,b]", QueueLog.ids(listOf("a", "b")))
        assertEquals("[a,b,…+3]", QueueLog.ids(listOf("a", "b", "c", "d", "e"), limit = 2))
    }

    @Test fun `logging without an Android logger never fails the caller`() {
        // Plain JVM unit tests have only stub android.util.Log.
        QueueLog.i { "restore applied" }
    }
}
