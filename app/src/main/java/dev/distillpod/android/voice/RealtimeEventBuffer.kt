package dev.distillpod.android.voice

import kotlinx.coroutines.channels.Channel

/** Audio can arrive faster than real-time playback. Bound queued payload size, not a short burst's chunk count. */
internal class RealtimeEventBuffer(
    private val maxCharacters: Int = 4 * 1024 * 1024,
    private val maxEvents: Int = 8192,
) {
    private val channel = Channel<String>(Channel.UNLIMITED)
    private var characters = 0
    private var count = 0

    @Synchronized fun offer(text: String): Boolean {
        if (text.length > 512_000 || text.length > maxCharacters - characters || count >= maxEvents) {
            close(IllegalStateException("Voice response exceeded buffer limit"))
            return false
        }
        characters += text.length
        count++
        if (channel.trySend(text).isSuccess) return true
        characters -= text.length
        count--
        return false
    }
    suspend fun receive(): String {
        val text = channel.receive()
        synchronized(this) { characters -= text.length; count-- }
        return text
    }
    fun close(error: Throwable? = null) { channel.close(error) }
    fun cancel() { channel.cancel() }
}
