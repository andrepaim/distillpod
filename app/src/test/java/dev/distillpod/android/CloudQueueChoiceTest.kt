package dev.distillpod.android

import dev.distillpod.android.playback.CloudQueueChoice
import dev.distillpod.android.playback.CloudQueueChoices
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CloudQueueChoiceTest {
    @Test fun `cancel before writer ownership cannot execute resolution`()=runBlocking {
        val choice=CloudQueueChoice(coroutineContext[Job],resolve={},imported={})
        choice.completion.cancel()
        assertFalse(choice.start())
    }
    @Test fun `caller cancellation after ownership does not cancel service work`()=runBlocking {
        val caller=Job()
        val choice=CloudQueueChoice(caller,resolve={},imported={})
        assertTrue(choice.start())
        caller.cancel()
        assertTrue(choice.completion.isCancelled)
        // start is a single-use transfer, independent of the caller's completion cancellation.
        assertFalse(choice.start())
        var ran=false
        withContext(Dispatchers.Default){choice.resolve();ran=true}
        assertTrue(ran)
    }
    @Test fun `capabilities are single use bounded and disappear on cancellation`()=runBlocking {
        val registry=CloudQueueChoices()
        val first=CloudQueueChoice(coroutineContext[Job],resolve={},imported={})
        val token=registry.register(first)
        val other=CloudQueueChoice(coroutineContext[Job],resolve={},imported={})
        try {registry.register(other);fail("A second pending capability must be rejected")}
        catch(expected:IllegalStateException) {other.completion.cancel()}
        assertNull(registry.take("not-a-capability"))
        assertSame(first,registry.take(token));assertNull(registry.take(token))
        first.completion.complete(Unit)
        val cancelled=CloudQueueChoice(coroutineContext[Job],resolve={},imported={})
        val cancelledToken=registry.register(cancelled)
        cancelled.completion.cancel()
        assertNull(registry.take(cancelledToken))
    }
}
