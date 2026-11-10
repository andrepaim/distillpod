package dev.distillpod.android.playback

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

/** Cancellation may prevent starting, but cannot cancel the service's already owned transaction. */
internal class CloudQueueChoice(parent: Job? = null,
    val resolve: suspend () -> Unit, val imported: (List<String>) -> Unit) {
    val completion = CompletableDeferred<Unit>(parent)
    private val started = AtomicBoolean()
    fun start(): Boolean = !completion.isCancelled && started.compareAndSet(false,true)
}

/** An opaque, bounded, single-use bridge for this process's private MediaSession command. */
internal class CloudQueueChoices {
    private var pending: Pair<String,CloudQueueChoice>? = null
    @Synchronized fun register(choice:CloudQueueChoice):String {
        check(pending==null) { "A queue choice is already pending" }
        val token=UUID.randomUUID().toString()
        pending=token to choice
        choice.completion.invokeOnCompletion { synchronized(this) {
            if(pending?.first==token)pending=null
        } }
        return token
    }
    @Synchronized fun take(token:String?):CloudQueueChoice? {
        val value=pending?.takeIf {it.first==token} ?: return null
        pending=null
        return value.second
    }
}
