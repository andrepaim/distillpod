package dev.distillpod.core

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/**
 * One sign-in attempt, allowing browser preconnections and unrelated requests.
 *
 * After the first valid callback the listener keeps answering exact repeats of that callback with the same
 * success page, so a browser that requests the redirect more than once does not end on a refused connection.
 * It does so for at most [repeatGraceMs], never past the attempt deadline, and stops at [close]. Nothing else
 * is accepted in that window, and handing the first callback to the caller never waits for it.
 */
class LoopbackCallbackServer(
    private val timeoutMs: Long = 600_000,
    private val readTimeoutMs: Int = 2000,
    port: Int = 0,
    private val repeatGraceMs: Long = REPEAT_GRACE_MS,
) : Closeable {
    init { require(timeoutMs in 1..600_000 && readTimeoutMs in 1..10_000 && repeatGraceMs in 0..REPEAT_GRACE_MS) }
    private val listener = ServerSocket(port, 5, InetAddress.getByName("127.0.0.1"))
    private val origin = "http://127.0.0.1:${listener.localPort}"
    val redirectUri = "$origin/auth/callback"
    private val lock = Any()
    @Volatile private var closed = false
    private var started = false
    private var active: Socket? = null

    fun awaitCallback(attempt: OAuthAttempt): String {
        require(attempt.redirectUri == redirectUri) { "Callback listener does not match the pending attempt" }
        synchronized(lock) { check(!started) { "A callback listener serves one attempt" }; started = true }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        try {
            // The browser socket is closed before returning, i.e. before the potentially slow token exchange.
            val callback = serve(deadline) { receive(it, attempt, deadline) }
            checkActive()
            if (callback == null) throw SocketTimeoutException("Sign-in timed out. Return to DistillPod and try again.")
            answerRepeats(callback.removePrefix(origin), minOf(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(repeatGraceMs)))
            return callback
        } catch (error: SocketException) {
            checkActive()
            throw error
        }
    }

    /** Serves one connection at a time until [handle] yields a result, the deadline passes (null), or [close]. */
    private fun <T : Any> serve(deadline: Long, handle: (Socket) -> T?): T? {
        while (true) {
            checkActive()
            val left = deadline - System.nanoTime()
            if (left <= 0) return null
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(left).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            listener.soTimeout = remainingMs
            val socket = try { listener.accept() } catch (_: SocketTimeoutException) { return null }
            synchronized(lock) {
                if (closed) { socket.close(); checkActive() }
                active = socket
            }
            try {
                socket.soTimeout = minOf(readTimeoutMs, remainingMs)
                socket.use { handle(it) }?.let { return it }
            } finally {
                synchronized(lock) { if (active === socket) active = null }
            }
        }
    }

    /** Answers exact repeats of the accepted callback on a short-lived thread, then closes the listener. */
    private fun answerRepeats(target: String, deadline: Long) {
        val worker = Thread({
            try {
                serve<Unit>(deadline) { socket ->
                    val requested = try { readTarget(socket, deadline) } catch (_: IOException) { null }
                    when {
                        requested == null -> Unit
                        requested == target -> respond(socket, 200, RECEIVED)
                        !requested.startsWith("/auth/callback?") -> respond(socket, 404, UNKNOWN_PATH)
                        else -> respond(socket, 400, INVALID_CALLBACK)
                    }
                    null
                }
            } catch (_: Exception) {
                // Closed or cancelled: nothing is waiting on this thread.
            } finally { close() }
        }, "loopback-callback-repeats-${listener.localPort}")
        worker.isDaemon = true
        synchronized(lock) { if (closed) return; worker.start() }
    }

    private fun receive(socket: Socket, attempt: OAuthAttempt, deadline: Long): String? {
        val target = try { readTarget(socket, deadline) } catch (_: IOException) { return null }
        if (!target.startsWith("/auth/callback?")) {
            respond(socket, 404, UNKNOWN_PATH)
            return null
        }
        val callback = "$origin$target"
        try {
            attempt.callback(callback)
        } catch (denied: ConsentDenied) {
            respond(socket, 200, "Authorization declined. Return to DistillPod.")
            throw denied
        } catch (_: IllegalArgumentException) {
            respond(socket, 400, INVALID_CALLBACK)
            return null
        }
        checkActive()
        respond(socket, 200, RECEIVED)
        return callback
    }

    private fun readTarget(socket: Socket, deadline: Long): String {
        val input = socket.getInputStream().buffered()
        var bytes = 0
        fun line(): String {
            val text = StringBuilder()
            while (true) {
                checkActive()
                if (System.nanoTime() >= deadline) throw SocketTimeoutException()
                val next = input.read()
                if (next < 0 || ++bytes > 32768 || text.length >= 16384) throw IOException("Invalid callback request")
                if (next == 10) return text.toString().removeSuffix("\r")
                if (next != 13 && next !in 32..126) throw IOException("Invalid callback request")
                text.append(next.toChar())
            }
        }
        val request = line().split(' ')
        if (request.size != 3 || request[0] != "GET" || request[2] !in listOf("HTTP/1.0", "HTTP/1.1")) throw IOException("Expected an HTTP GET callback")
        while (line().isNotEmpty()) { /* Consume the bounded headers before closing the socket. */ }
        return request[1]
    }

    private fun respond(socket: Socket, status: Int, body: String) {
        val reason = when (status) { 200 -> "OK"; 404 -> "Not Found"; else -> "Bad Request" }
        val content = body.toByteArray(Charsets.UTF_8)
        try {
            socket.getOutputStream().apply {
                write(("HTTP/1.1 $status $reason\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${content.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                write(content); flush()
            }
        } catch (_: IOException) { /* A closed browser must not discard an otherwise valid grant. */ }
    }

    private fun checkActive() { if (closed) throw CancellationException("Sign-in cancelled") }

    override fun close() {
        synchronized(lock) {
            closed = true
            try { active?.close() } finally { listener.close() }
        }
    }

    companion object {
        /** Long enough for a browser preload, retry or reload; short enough that the port is soon released. */
        const val REPEAT_GRACE_MS = 60_000L
        const val RECEIVED = "Connection received. Return to DistillPod now to finish signing in. You can close this page."
        private const val UNKNOWN_PATH = "Unknown callback path."
        private const val INVALID_CALLBACK = "Invalid callback. Return to the active sign-in attempt."
    }
}
