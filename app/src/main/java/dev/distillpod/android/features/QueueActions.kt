package dev.distillpod.android.features

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Retain rapid queue edits in tap order instead of dropping them while another edit suspends. */
internal class QueueActions(scope: CoroutineScope, onError: suspend (Exception) -> Unit) {
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    init {
        scope.launch {
            try {
                for (command in commands) {
                    try { command() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { onError(error) }
                }
            } finally { commands.cancel() }
        }
    }
    fun submit(command: suspend () -> Unit) { commands.trySend(command) }
}
