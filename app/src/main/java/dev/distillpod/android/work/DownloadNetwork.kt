package dev.distillpod.android.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** The app's default network as Android reports it to DistillPod. */
data class NetworkAccess(val blocked: Boolean, val metered: Boolean)

/**
 * Tells Android's per-app network block apart from a missing network (#292). Android 15+ blocks the
 * network of an app whose process is cached, about five seconds after its last running job ends. A
 * transfer whose foreground service was refused then loses its connection, and every new request
 * fails at once, until a job binds the process again or the user opens the app.
 *
 * [states] opens a fresh view of the default network on each collection: null while there is none.
 */
class DownloadNetwork(internal val states: () -> Flow<NetworkAccess?>, private val reportMillis: Long = 10_000,
    private val recheckMillis: Long = 60_000) {
    /** After a failed request: whether Android blocks the network. The report can follow the broken connection. */
    suspend fun blockedAfterFailure(): Boolean =
        withTimeoutOrNull(reportMillis) { states().first { it?.blocked == true } } != null

    /**
     * Suspends until the default network is present, not blocked, and allowed by the download setting
     * ([unmeteredOnly]). Each recheck opens a new view and reads the setting again, so a status change
     * that never arrived (for example while the process was frozen) cannot strand the wait.
     */
    suspend fun awaitUsable(unmeteredOnly: suspend () -> Boolean) {
        while (true) {
            val unmetered = unmeteredOnly()
            val usable = withTimeoutOrNull(recheckMillis) {
                states().first { it != null && !it.blocked && !(unmetered && it.metered) }
            }
            if (usable != null) return
        }
    }

    companion object {
        fun system(context: Context) = DownloadNetwork({ defaultNetwork(context.applicationContext) })
    }
}

/**
 * Since API 29 the default network callback also reports a network that Android blocks for this app,
 * with onBlockedStatusChanged after onAvailable and onCapabilitiesChanged. Nothing is emitted until the
 * blocked status of the current network is known.
 */
private fun defaultNetwork(context: Context): Flow<NetworkAccess?> = callbackFlow {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val callback = object : ConnectivityManager.NetworkCallback() {
        private var current: Network? = null
        private var blocked: Boolean? = null
        private var metered = true
        override fun onAvailable(network: Network) {
            if (network != current) { current = network; blocked = null; metered = true }
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network != current) return
            metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            publish()
        }
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            if (network != current) return
            this.blocked = blocked
            publish()
        }
        override fun onLost(network: Network) {
            if (network != current) return
            current = null; blocked = null
            trySend(null)
        }
        private fun publish() { blocked?.let { trySend(NetworkAccess(it, metered)) } }
    }
    // Too many registered callbacks throws; the view then stays unknown, which never counts as blocked.
    val registered = runCatching { connectivity.registerDefaultNetworkCallback(callback) }.isSuccess
    awaitClose { if (registered) runCatching { connectivity.unregisterNetworkCallback(callback) } }
}.conflate()
