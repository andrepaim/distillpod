package dev.distillpod.android.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticBarrierProbeTest {
    @Test fun `failed barrier retains original and identifies its actual gated worker and queue`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val probe = DiagnosticBarrierProbe(Diagnostics(context, ""))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val original = IllegalStateException("Synthetic original barrier failure")
        try {
            probe.executor.execute { entered.countDown(); release.await() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            probe.worker.get().name = "PRIVATE_SYNTHETIC_THREAD_NAME"
            probe.executor.submit(object : Runnable {
                override fun run() {}
                override fun toString() = "PRIVATE_SYNTHETIC_QUEUED_CONTENT"
            })
            try {
                probe.awaitIdle { throw original }
                fail("The original barrier failure must propagate")
            } catch (failure: IllegalStateException) {
                assertSame(original, failure)
                val evidence = failure.suppressed.single().message.orEmpty()
                assertTrue(evidence, evidence.contains("active=1 queue=1"))
                assertTrue(evidence, evidence.contains("worker:"))
                assertTrue(evidence, evidence.contains("CountDownLatch.await"))
                assertFalse(evidence, evidence.contains("PRIVATE_SYNTHETIC"))
            }
        } finally {
            release.countDown()
            probe.executor.shutdown()
            assertTrue(probe.executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
