package dev.distillpod.core

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

    @Test fun `missing callback has a bounded timeout`() {
        LoopbackCallbackServer(timeoutMs = 100).use { server ->
            assertThrows(SocketTimeoutException::class.java) { server.awaitCallback(attempt(server)) }
        }
    }
}
