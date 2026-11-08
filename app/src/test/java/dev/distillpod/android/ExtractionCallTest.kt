package dev.distillpod.android

import dev.distillpod.android.video.ExtractionCall
import dev.distillpod.android.video.YouTubeFailure
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*

class ExtractionCallTest {
    @Test fun `deadline destroys only its own process and interrupts the worker`() {
        val workers = Executors.newFixedThreadPool(2)
        val stopped = CountDownLatch(1)
        val ids = mutableListOf<String>()
        try {
            val failure = runCatching { ExtractionCall(execute = { _: String ->
                try { CountDownLatch(1).await(); "never" } finally { stopped.countDown() }
            }, destroy = { ids.add(it) }, timeoutMs = 100, executor = workers).run() }.exceptionOrNull()
            assertEquals("timeout", (failure as YouTubeFailure).category)
            assertTrue(stopped.await(2, TimeUnit.SECONDS))
            assertEquals(1, ids.size)
            assertNotNull(java.util.UUID.fromString(ids.single()))
            assertEquals("working", ExtractionCall({ "working" }, { error("Successful process must not be destroyed") }, executor = workers).run())
        } finally { workers.shutdownNow() }
    }

    @Test fun `caller cancellation destroys its process and does not leak extractor text`() {
        val workers = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        val caller = Thread {
            try { ExtractionCall(execute = { _: String ->
                entered.countDown()
                try { CountDownLatch(1).await(); Unit } finally { stopped.countDown() }
            }, destroy = { destroyed.countDown() }, executor = workers).run() }
            catch (_: InterruptedException) { /* Expected cancellation. */ }
        }
        try {
            caller.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); caller.interrupt(); caller.join(2_000)
            assertFalse(caller.isAlive); assertTrue(destroyed.await(2, TimeUnit.SECONDS)); assertTrue(stopped.await(2, TimeUnit.SECONDS))
            val safe = runCatching { ExtractionCall<Unit>({ throw IllegalStateException("private URL and title") }, {}, executor = workers).run() }.exceptionOrNull()!!
            assertFalse(safe.message!!.contains("private")); assertNull(safe.cause)
        } finally { caller.interrupt(); workers.shutdownNow() }
    }

    @Test fun `restricted and throttled failures are not retried`() {
        assertFalse(YouTubeFailure.classify("Sign in to confirm you're not a bot").retryable)
        assertEquals("restricted", YouTubeFailure.classify("Private video").category)
        assertTrue(YouTubeFailure.classify("Connection timed out").retryable)
    }
}
