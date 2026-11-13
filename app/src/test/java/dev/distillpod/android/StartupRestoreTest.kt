package dev.distillpod.android

import dev.distillpod.android.playback.runStartupRestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** #148 item 10: a failed or cancelled startup restore must never leave playback restoring. */
class StartupRestoreTest {
    private class Fixture {
        val ready = CompletableDeferred<Unit>()
        var restoring = true
        val events = mutableListOf<String>()
        val failures = mutableListOf<Exception>()
        val release = { events += "release"; restoring = false; ready.complete(Unit); Unit }
    }

    @Test fun `a successful restore applies the queue before releasing readiness`() = runTest {
        val f = Fixture()
        val applied = runStartupRestore(load = { listOf("a", "b") }, apply = { f.events += "apply:$it" },
            onFailure = { f.failures += it }, release = f.release)
        assertTrue(applied)
        assertEquals(listOf("apply:[a, b]", "release"), f.events)
        assertTrue(f.ready.isCompleted)
        assertFalse(f.restoring)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun `a failed load is reported and still releases readiness`() = runTest {
        val f = Fixture()
        val error = IllegalStateException("Episode is unavailable")
        val applied = runStartupRestore<List<String>>(load = { throw error }, apply = { f.events += "apply" },
            onFailure = { f.failures += it }, release = f.release)
        assertFalse(applied)
        assertEquals(listOf("release"), f.events)
        assertEquals(listOf<Exception>(error), f.failures)
        assertTrue(f.ready.isCompleted)
        assertFalse(f.restoring)
    }

    @Test fun `a failure while applying is reported and releases readiness once`() = runTest {
        val f = Fixture()
        val error = IllegalArgumentException("bad index")
        val applied = runStartupRestore(load = { 1 }, apply = { throw error },
            onFailure = { f.failures += it }, release = f.release)
        assertFalse(applied)
        assertEquals(listOf("release"), f.events)
        assertEquals(listOf<Exception>(error), f.failures)
        assertFalse(f.restoring)
    }

    @Test fun `cancellation releases readiness without reporting a failure`() = runTest {
        val f = Fixture()
        val loading = CompletableDeferred<Unit>()
        val restore = async {
            runStartupRestore<Unit>(load = { loading.complete(Unit); awaitCancellation() }, apply = { f.events += "apply" },
                onFailure = { f.failures += it }, release = f.release)
        }
        loading.await()
        restore.cancel()
        try { restore.await(); fail("Cancellation must propagate") } catch (_: CancellationException) {}
        assertEquals(listOf("release"), f.events)
        assertTrue(f.failures.isEmpty())
        assertTrue(f.ready.isCompleted)
    }
}
