package dev.distillpod.android

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.core.LoopbackCallbackServer
import dev.distillpod.core.OAuthAttempt
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoopbackCallbackDeviceTest {
    @Test fun browserCallbackSurvivesAnIdlePreconnection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // The owner must unlock first; this test never dismisses a secure keyguard.
        val keyguard = instrumentation.targetContext.getSystemService(android.app.KeyguardManager::class.java)
        assertFalse("Unlock the device before the browser callback test", keyguard.isKeyguardLocked)
        // Allow a cold browser process on a shared CI runner; the idle socket still
        // expires after 500 ms and the callback/state assertions remain mandatory.
        LoopbackCallbackServer(timeoutMs = 60000, readTimeoutMs = 500).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val attempt = OAuthAttempt("public-probe-state", "probe-nonce", "probe-verifier", server.redirectUri)
                val received = executor.submit<String> { server.awaitCallback(attempt) }
                Socket("127.0.0.1", URI(server.redirectUri).port).use {
                    val probe = server.redirectUri + "?state=public-probe-state&code=public-probe-code&client_id=public-probe-client"
                    instrumentation.targetContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(probe)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    val callback = received.get(45, TimeUnit.SECONDS)
                    assertEquals("public-probe-client", attempt.callback(callback).clientId)
                }
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun callbackHttpResponseCompletesWhileListenerScopeIsStillOpen() {
        LoopbackCallbackServer(timeoutMs = 20000, readTimeoutMs = 500).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val attempt = OAuthAttempt("public-http-state", "probe-nonce", "probe-verifier", server.redirectUri)
                val received = executor.submit<String> { server.awaitCallback(attempt) }
                Socket("127.0.0.1", URI(server.redirectUri).port).use { idle ->
                    Socket("127.0.0.1", URI(server.redirectUri).port).use { client ->
                        client.soTimeout = 15000
                        val target = "/auth/callback?state=public-http-state&code=public-http-code&client_id=public-http-client"
                        client.getOutputStream().write("GET $target HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                        val response = client.getInputStream().bufferedReader().readText()
                        assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"))
                        assertTrue(response.contains("Connection: close\r\n"))
                        assertTrue(response.endsWith("Connection received. Return to DistillPod for the final result."))
                        assertEquals("public-http-client", attempt.callback(received.get(5, TimeUnit.SECONDS)).clientId)
                    }
                }
            } finally { executor.shutdownNow() }
        }
    }
}
