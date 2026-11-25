package dev.distillpod.android

import dev.distillpod.android.work.DownloadNetwork
import dev.distillpod.android.work.NetworkAccess
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test

/** Android's background network block, as downloads see it (#292). */
class DownloadNetworkTest {
    private val blocked = NetworkAccess(blocked = true, metered = false)
    private val usable = NetworkAccess(blocked = false, metered = false)

    @Test fun `a block reported shortly after the failure counts, a usable or missing network does not`() = runBlocking {
        val state = MutableStateFlow<NetworkAccess?>(usable)
        val network = DownloadNetwork({ state }, reportMillis = 1000)
        val check = async { network.blockedAfterFailure() }
        delay(200)
        state.value = blocked
        assertTrue(withTimeout(2000) { check.await() })
        assertTrue(network.blockedAfterFailure())
        for (other in listOf(usable, null)) {
            state.value = other
            assertFalse(DownloadNetwork({ state }, reportMillis = 300).blockedAfterFailure())
        }
    }

    @Test fun `waiting ends only on a usable network the download setting allows`() = runBlocking {
        val state = MutableStateFlow<NetworkAccess?>(blocked)
        var unmeteredOnly = true
        val network = DownloadNetwork({ state }, recheckMillis = 200)
        val wait = launch { network.awaitUsable { unmeteredOnly } }
        for (other in listOf(null, NetworkAccess(blocked = false, metered = true), blocked)) {
            state.value = other
            delay(500)
            assertTrue("still waiting on $other", wait.isActive)
        }
        state.value = NetworkAccess(blocked = false, metered = true)
        delay(300)
        assertTrue(wait.isActive)
        // The setting changed while waiting: the next recheck reads it.
        unmeteredOnly = false
        withTimeout(2000) { wait.join() }
    }

    @Test fun `a status change missed while the process was frozen is read on the next recheck`() = runBlocking {
        var current = blocked
        // Each collection reports the state once and then never again, like a lost callback.
        val network = DownloadNetwork({ flow { emit(current); awaitCancellation() } }, recheckMillis = 200)
        val wait = launch { network.awaitUsable { true } }
        delay(500)
        assertTrue(wait.isActive)
        current = usable
        withTimeout(2000) { wait.join() }
    }
}
