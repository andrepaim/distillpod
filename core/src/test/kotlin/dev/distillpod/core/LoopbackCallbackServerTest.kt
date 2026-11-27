package dev.distillpod.core

import java.net.ConnectException
import java.net.Socket
import java.net.URI
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LoopbackCallbackServerTest {
    private fun attempt(server: LoopbackCallbackServer) = OAuthAttempt("expected-state", "nonce", "verifier", server.redirectUri)
    private fun request(server: LoopbackCallbackServer, target: String): String = Socket("127.0.0.1", URI(server.redirectUri).port).use { socket ->
        socket.soTimeout = 2000
        socket.getOutputStream().write("GET $target HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
        socket.getInputStream().bufferedReader().readText()
    }
    private val valid = "/auth/callback?state=expected-state&code=test-code&client_id=issued-client"

    @Test fun `idle browser preconnection does not consume the sign-in attempt`() {
        LoopbackCallbackServer(timeoutMs = 3000, readTimeoutMs = 100).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val received = executor.submit<String> { server.awaitCallback(attempt(server)) }
                Socket("127.0.0.1", URI(server.redirectUri).port).use {
                    // Queue the real request behind an idle preconnection.
                    val response = request(server, valid)
                    assertTrue(response.startsWith("HTTP/1.1 200"))
                    assertTrue(response.contains("Return to DistillPod"))
                }
                assertTrue(received.get(2, TimeUnit.SECONDS).endsWith(valid))
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun `unrelated paths and invalid state cannot terminate the pending attempt`() {
        LoopbackCallbackServer(timeoutMs = 3000).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val received = executor.submit<String> { server.awaitCallback(attempt(server)) }
                assertTrue(request(server, "/favicon.ico").startsWith("HTTP/1.1 404"))
                assertTrue(request(server, valid.replace("expected-state", "wrong-state")).startsWith("HTTP/1.1 400"))
                assertTrue(request(server, valid).startsWith("HTTP/1.1 200"))
                assertTrue(received.get(2, TimeUnit.SECONDS).contains("code=test-code"))
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun `valid consent denial completes with a clear rejection`() {
        LoopbackCallbackServer(timeoutMs = 3000).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val received = executor.submit<Boolean> {
                    try { server.awaitCallback(attempt(server)); false } catch (_: ConsentDenied) { true }
                }
                assertTrue(request(server, "/auth/callback?state=expected-state&error=access_denied").startsWith("HTTP/1.1 200"))
                assertTrue(received.get(2, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun `closing a pending listener stops it promptly`() {
        val server = LoopbackCallbackServer(timeoutMs = 3000)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val received = executor.submit<Boolean> {
                try { server.awaitCallback(attempt(server)); false } catch (_: java.util.concurrent.CancellationException) { true }
            }
            server.close()
            assertTrue(received.get(1, TimeUnit.SECONDS))
        } finally { server.close(); executor.shutdownNow() }
    }

    @Test fun `a browser repeating the accepted callback gets the same success page every time`() {
        LoopbackCallbackServer(timeoutMs = 5000).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val received = executor.submit<String> { server.awaitCallback(attempt(server)) }
                val first = request(server, valid)
                assertTrue(first.startsWith("HTTP/1.1 200"))
                assertTrue(received.get(2, TimeUnit.SECONDS).endsWith(valid))
                // A preload or retry repeats the redirect on fresh connections after the app has the code.
                assertEquals(first, request(server, valid))
                assertEquals(first, request(server, valid))
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun `an idle preconnection after the accepted callback does not stop the repeat answers`() {
        LoopbackCallbackServer(timeoutMs = 5000, readTimeoutMs = 100).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val received = executor.submit<String> { server.awaitCallback(attempt(server)) }
                val first = request(server, valid)
                received.get(2, TimeUnit.SECONDS)
                Socket("127.0.0.1", URI(server.redirectUri).port).use {
                    assertEquals(first, request(server, valid))
                }
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun `after the accepted callback any other request is answered as before and never accepted`() {
        LoopbackCallbackServer(timeoutMs = 5000).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val received = executor.submit<String> { server.awaitCallback(attempt(server)) }
                assertTrue(request(server, valid).startsWith("HTTP/1.1 200"))
                assertTrue(received.get(2, TimeUnit.SECONDS).endsWith(valid))
                assertTrue(request(server, valid.replace("expected-state", "wrong-state")).startsWith("HTTP/1.1 400"))
                // Same state, different code: a second grant must never be taken for the attempt.
                assertTrue(request(server, valid.replace("test-code", "other-code")).startsWith("HTTP/1.1 400"))
                assertTrue(request(server, "/auth/callback?state=expected-state&error=access_denied").startsWith("HTTP/1.1 400"))
                assertTrue(request(server, "/favicon.ico").startsWith("HTTP/1.1 404"))
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun `the success page tells the user to return to DistillPod now`() {
        LoopbackCallbackServer(timeoutMs = 5000).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                executor.submit<String> { server.awaitCallback(attempt(server)) }
                val body = request(server, valid).substringAfter("\r\n\r\n")
                assertTrue(body, body.startsWith("Connection received."))
                assertTrue(body, body.contains("Return to DistillPod now"))
            } finally { executor.shutdownNow() }
        }
    }

    private fun port(server: LoopbackCallbackServer) = URI(server.redirectUri).port
    private fun repeatThreadAlive(port: Int) = Thread.getAllStackTraces().keys.any { it.name == "loopback-callback-repeats-$port" && it.isAlive }
    private fun assertFullyClosed(port: Int) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (repeatThreadAlive(port) && System.nanoTime() < until) Thread.sleep(10)
        assertFalse("The repeat thread outlived the listener", repeatThreadAlive(port))
        assertThrows(ConnectException::class.java) { Socket("127.0.0.1", port).close() }
    }

    @Test fun `the first callback reaches the app without waiting for the repeat window`() {
        LoopbackCallbackServer(timeoutMs = 20_000, repeatGraceMs = 15_000).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val received = executor.submit<String> { server.awaitCallback(attempt(server)) }
                val first = request(server, valid)
                assertTrue(received.get(1, TimeUnit.SECONDS).endsWith(valid))
                assertEquals(first, request(server, valid))
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun `the repeat window ends at its bound and leaves no listener or thread`() {
        LoopbackCallbackServer(timeoutMs = 20_000, repeatGraceMs = 300).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val received = executor.submit<String> { server.awaitCallback(attempt(server)) }
                request(server, valid)
                received.get(1, TimeUnit.SECONDS)
                assertTrue(repeatThreadAlive(port(server)))
                Thread.sleep(500)
                assertFullyClosed(port(server))
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun `the repeat window never extends the attempt lifetime`() {
        LoopbackCallbackServer(timeoutMs = 400, repeatGraceMs = 60_000).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val received = executor.submit<String> { server.awaitCallback(attempt(server)) }
                request(server, valid)
                received.get(1, TimeUnit.SECONDS)
                Thread.sleep(600)
                assertFullyClosed(port(server))
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun `cancelling during the repeat window closes the listener, open connections and the thread`() {
        val server = LoopbackCallbackServer(timeoutMs = 20_000, readTimeoutMs = 5000, repeatGraceMs = 60_000)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val received = executor.submit<String> { server.awaitCallback(attempt(server)) }
            request(server, valid)
            received.get(1, TimeUnit.SECONDS)
            Socket("127.0.0.1", port(server)).use { idle ->
                idle.soTimeout = 2000
                Thread.sleep(100) // Let the repeat thread accept and hold the idle connection.
                server.close()
                val end = try { idle.getInputStream().read() } catch (_: java.net.SocketException) { -1 }
                assertEquals(-1, end)
            }
            assertFullyClosed(port(server))
        } finally { server.close(); executor.shutdownNow() }
    }

    @Test fun `missing callback has a bounded timeout`() {
        LoopbackCallbackServer(timeoutMs = 100).use { server ->
            assertThrows(SocketTimeoutException::class.java) { server.awaitCallback(attempt(server)) }
        }
    }
}
