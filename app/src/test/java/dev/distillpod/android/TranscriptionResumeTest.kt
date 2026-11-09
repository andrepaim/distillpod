package dev.distillpod.android

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionResumeTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun readyGraphRecoversAfterResumeEventAndOnSubsequentResumes() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val owner = Owner()
        try {
            owner.registry.currentState = Lifecycle.State.STARTED
            var recoveries = 0
            owner.observeTranscriptionRecovery({}, { recoveries++ })
            assertEquals(0, recoveries)
            owner.registry.currentState = Lifecycle.State.RESUMED
            runCurrent()
            assertEquals(1, recoveries)
            owner.registry.currentState = Lifecycle.State.STARTED
            owner.registry.currentState = Lifecycle.State.RESUMED
            runCurrent()
            assertEquals(2, recoveries)
        } finally {
            owner.registry.currentState = Lifecycle.State.DESTROYED
            Dispatchers.resetMain()
        }
    }

    @Test fun initializationCompletingInBackgroundWaitsForNextResume() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val owner = Owner()
        val ready = CompletableDeferred<Unit>()
        try {
            owner.registry.currentState = Lifecycle.State.CREATED
            var recoveries = 0
            owner.observeTranscriptionRecovery({ ready.await() }, { recoveries++ })
            owner.registry.currentState = Lifecycle.State.RESUMED
            runCurrent()
            owner.registry.currentState = Lifecycle.State.STARTED
            ready.complete(Unit)
            runCurrent()
            assertEquals(0, recoveries)
            owner.registry.currentState = Lifecycle.State.RESUMED
            runCurrent()
            assertEquals(1, recoveries)
        } finally {
            owner.registry.currentState = Lifecycle.State.DESTROYED
            Dispatchers.resetMain()
        }
    }

    @Test fun destroyedActivityCannotRecoverAfterInitialization() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val owner = Owner()
        val ready = CompletableDeferred<Unit>()
        try {
            owner.registry.currentState = Lifecycle.State.CREATED
            var recoveries = 0
            owner.observeTranscriptionRecovery({ ready.await() }, { recoveries++ })
            owner.registry.currentState = Lifecycle.State.RESUMED
            runCurrent()
            owner.registry.currentState = Lifecycle.State.DESTROYED
            ready.complete(Unit)
            runCurrent()
            assertEquals(0, recoveries)
        } finally {
            owner.registry.currentState = Lifecycle.State.DESTROYED
            Dispatchers.resetMain()
        }
    }
}
