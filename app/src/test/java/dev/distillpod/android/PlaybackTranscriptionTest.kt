package dev.distillpod.android

import dev.distillpod.android.playback.PlaybackTranscription
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackTranscriptionTest {
    @Test fun `waits for persisted setting and enabling evaluates the playing episode`() = runTest {
        val enabled = MutableSharedFlow<Boolean>(replay = 1)
        val requests = mutableListOf<String>()
        val observer = PlaybackTranscription(backgroundScope, enabled, { requests += it }, { throw it })
        observer.update("one", true)
        runCurrent()
        assertTrue(requests.isEmpty())
        enabled.emit(false)
        runCurrent()
        assertTrue(requests.isEmpty())
        enabled.emit(true)
        runCurrent()
        assertEquals(listOf("one"), requests)
        enabled.emit(false)
        observer.update("two", true)
        runCurrent()
        assertEquals(listOf("one"), requests)
        enabled.emit(true)
        runCurrent()
        assertEquals(listOf("one", "two"), requests)
    }

    @Test fun `paused restoration does nothing and continuous queue transitions prepare the next episode`() = runTest {
        val requests = mutableListOf<String>()
        val observer = PlaybackTranscription(backgroundScope, MutableStateFlow(true), { requests += it }, { throw it })
        observer.update("one", false)
        runCurrent()
        assertTrue(requests.isEmpty())
        observer.update("one", true)
        runCurrent()
        repeat(10) { observer.update("one", true); runCurrent() } // Seek, snapshots, downloaded-file replacement.
        assertEquals(listOf("one"), requests)
        observer.update("two", true)
        runCurrent()
        assertEquals(listOf("one", "two"), requests)
        observer.update("two", false)
        runCurrent()
        observer.update("two", true)
        runCurrent()
        assertEquals(listOf("one", "two", "two"), requests) // Repository makes resume/buffering idempotent.
    }

    @Test fun `pausing or disabling does not cancel accepted preparation`() = runTest {
        val enabled = MutableStateFlow(true)
        val finish = CompletableDeferred<Unit>()
        var completed = false
        val observer = PlaybackTranscription(backgroundScope, enabled, { finish.await(); completed = true }, { throw it })
        observer.update("one", true)
        runCurrent()
        enabled.value = false
        observer.update("one", false)
        runCurrent()
        finish.complete(Unit)
        runCurrent()
        assertTrue(completed)
    }

    @Test fun `queue transitions are retained while an earlier preparation is waiting`() = runTest {
        val finish = CompletableDeferred<Unit>()
        val requests = mutableListOf<String>()
        val observer = PlaybackTranscription(backgroundScope, MutableStateFlow(true), {
            requests += it
            if (it == "one") finish.await()
        }, { throw it })
        observer.update("one", true)
        runCurrent()
        observer.update("two", true)
        runCurrent()
        observer.update("three", true)
        runCurrent()
        finish.complete(Unit)
        runCurrent()
        assertEquals(listOf("one", "two", "three"), requests)
    }

    @Test fun `preparation failure does not stop observation of later playback`() = runTest {
        val requests = mutableListOf<String>()
        var failures = 0
        val observer = PlaybackTranscription(backgroundScope, MutableStateFlow(true), {
            if (it == "bad") error("Cannot schedule")
            requests += it
        }, { failures++ })
        observer.update("bad", true)
        runCurrent()
        observer.update("good", true)
        runCurrent()
        assertEquals(1, failures)
        assertEquals(listOf("good"), requests)
    }
}
