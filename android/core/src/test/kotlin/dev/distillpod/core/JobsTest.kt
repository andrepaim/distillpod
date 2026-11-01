package dev.distillpod.core
import org.junit.Assert.*
import org.junit.Test

class JobsTest {
    @Test fun `completion requires a completed inference event`() {
        val j = InferenceAttempt("request-1")
        j.start(); j.append("partial")
        assertEquals(JobState.RUNNING, j.state)
        j.interrupt(); assertEquals(JobState.PAUSED, j.state)
        assertNull(j.result)
        j.start(); j.append("finished"); j.complete()
        assertEquals("finished", j.result)
    }
    @Test fun `quota stops automatic processing`() {
        val j = InferenceAttempt("r"); j.start(); j.waitForQuota()
        assertEquals(JobState.WAITING_FOR_QUOTA, j.state)
        assertThrows(IllegalStateException::class.java) { j.complete() }
    }
    @Test fun `cancellation cannot publish output`() {
        val j = InferenceAttempt("r"); j.start(); j.cancel()
        assertThrows(IllegalStateException::class.java) { j.append("late") }
        assertNull(j.result)
    }
    @Test fun `progress is bounded and completion is explicit`() {
        val p = ListeningProgress(1000, 10000)
        assertFalse(p.completed)
        assertThrows(IllegalArgumentException::class.java) { ListeningProgress(-1, 10000) }
        assertThrows(IllegalArgumentException::class.java) { ListeningProgress(11000, 10000) }
    }
}
