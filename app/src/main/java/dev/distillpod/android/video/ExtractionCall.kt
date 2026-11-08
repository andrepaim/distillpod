package dev.distillpod.android.video

import java.io.IOException
import java.util.UUID
import java.util.concurrent.*

/** Owns one subprocess deadline and cancellation; never forwards extractor output to callers. */
internal class ExtractionCall<T>(
    private val execute: (String) -> T,
    private val destroy: (String) -> Unit,
    private val timeoutMs: Long = 40_000,
    private val executor: ExecutorService = workers,
) {
    fun run(): T {
        val id = UUID.randomUUID().toString()
        val future = try { executor.submit<T> { execute(id) } }
        catch (_: RejectedExecutionException) { throw YouTubeFailure("busy") }
        fun stop() { runCatching { destroy(id) }; future.cancel(true) }
        try { return future.get(timeoutMs, TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) { stop(); throw YouTubeFailure("timeout") }
        catch (e: InterruptedException) { stop(); throw e }
        catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is InterruptedException) throw cause
            throw if (cause is YouTubeFailure) cause else YouTubeFailure.classify(cause?.message.orEmpty())
        }
    }
    companion object {
        private val workers = ThreadPoolExecutor(3, 3, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8),
            ThreadFactory { Thread(it, "youtube-extraction").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    }
}

class YouTubeFailure(val category: String) : IOException(when (category) {
    "restricted" -> "This YouTube video is private, restricted or unavailable."
    "throttled" -> "YouTube is limiting requests. Please try again later."
    "timeout" -> "YouTube took too long to respond. Please retry."
    "busy" -> "YouTube is busy. Please retry shortly."
    "formats" -> "YouTube did not provide compatible media for this video."
    else -> "YouTube is unavailable. Check your connection and try again."
}) {
    val retryable get() = category in setOf("timeout", "network_or_storage")
    companion object {
        internal fun classify(output: String): YouTubeFailure {
            val text = output.take(16_384).lowercase()
            return YouTubeFailure(when {
                listOf("sign in to confirm", "not a bot", "429", "too many requests").any(text::contains) -> "throttled"
                listOf("private video", "age-restricted", "members-only", "video unavailable", "not available in your country", "removed").any(text::contains) -> "restricted"
                listOf("timed out", "timeout").any(text::contains) -> "timeout"
                listOf("signature", "nsig", "javascript", "challenge", "requested format").any(text::contains) -> "formats"
                else -> "network_or_storage"
            })
        }
    }
}
