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

/** One sign-in attempt, allowing browser preconnections and unrelated requests. */
class LoopbackCallbackServer(
    private val timeoutMs: Long = 600_000,
    private val readTimeoutMs: Int = 2000,
    port: Int = 0,
) : Closeable {
    init { require(timeoutMs in 1..600_000 && readTimeoutMs in 1..10_000) }
    private val listener = ServerSocket(port, 5, InetAddress.getByName("127.0.0.1"))
    val redirectUri = "http://127.0.0.1:${listener.localPort}/auth/callback"
    private val lock = Any()
    @Volatile private var closed = false
    private var active: Socket? = null

    fun awaitCallback(attempt: OAuthAttempt): String {
        require(attempt.redirectUri == redirectUri) { "Callback listener does not match the pending attempt" }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        fun remainingMs(): Int = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        try {
            while (System.nanoTime() < deadline) {
                checkActive()
                listener.soTimeout = remainingMs()
                val socket = try { listener.accept() } catch (_: SocketTimeoutException) { break }
                synchronized(lock) {
                    if (closed) { socket.close(); checkActive() }
                    active = socket
                }
                try {
                    socket.soTimeout = minOf(readTimeoutMs, remainingMs())
                    val callback = socket.use { receive(it, attempt, deadline) }
                    // Close the browser socket before the potentially slow token exchange.
                    if (callback != null) return callback
                } finally {
                    synchronized(lock) { if (active === socket) active = null }
                }
            }
            checkActive()
            throw SocketTimeoutException("Sign-in timed out. Return to DistillPod and try again.")
        } catch (error: SocketException) {
            checkActive()
            throw error
        }
    }

    private fun receive(socket: Socket, attempt: OAuthAttempt, deadline: Long): String? {
        val target = try { readTarget(socket, deadline) } catch (_: IOException) { return null }
        if (!target.startsWith("/auth/callback?")) {
            respond(socket, 404, "Unknown callback path.")
            return null
        }
        val callback = "http://127.0.0.1:${listener.localPort}$target"
        try {
            attempt.callback(callback)
        } catch (denied: ConsentDenied) {
            respond(socket, 200, "Authorization declined. Return to DistillPod.")
            throw denied
        } catch (_: IllegalArgumentException) {
            respond(socket, 400, "Invalid callback. Return to the active sign-in attempt.")
            return null
        }
        checkActive()
        respond(socket, 200, "Connection received. Return to DistillPod for the final result.")
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
}
