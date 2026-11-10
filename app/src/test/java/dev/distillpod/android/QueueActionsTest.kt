package dev.distillpod.android

import dev.distillpod.android.features.QueueActions
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueActionsTest {
    @Test fun `closing the scope cancels waiting and pending edits`() = runTest {
        val scope=CoroutineScope(coroutineContext + SupervisorJob())
        val gate=CompletableDeferred<Unit>()
        val applied=mutableListOf<Int>()
        val actions=QueueActions(scope) { throw it }
        actions.submit { gate.await();applied += 1 }
        actions.submit { applied += 2 }
        runCurrent()
        scope.cancel();gate.complete(Unit);runCurrent()
        actions.submit { applied += 3 };runCurrent()
        assertTrue(applied.isEmpty())
    }

    @Test fun `rapid commands retain submission order through suspensions and failures`() = runTest {
        val gate=CompletableDeferred<Unit>()
        val applied=mutableListOf<Int>()
        val errors=mutableListOf<Throwable>()
        val actions=QueueActions(backgroundScope) { errors += it }
        actions.submit { gate.await(); applied += 1 }
        actions.submit { applied += 2; error("fixture") }
        actions.submit { applied += 3 }
        runCurrent()
        assertTrue(applied.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(1,2,3),applied)
        assertEquals(1,errors.size)
    }
}
