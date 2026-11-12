package dev.distillpod.android
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubscriptionProviderTest {
    @Test fun `uses public response contract and waits for completion`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"type\":\"response.output_text.delta\",\"delta\":\"insight\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"))
            val provider = SubscriptionProvider(server.url("/v1/"))
            assertEquals("insight", provider.respond("token", "model", "instructions", "excerpt"))
            val request = server.takeRequest()
            assertEquals("/v1/responses", request.path)
            assertEquals("Bearer token", request.getHeader("Authorization"))
            val body = org.json.JSONObject(request.body.readUtf8())
            assertFalse(body.getBoolean("store")); assertTrue(body.getBoolean("stream"))
            assertFalse(body.has("temperature"))
        }
    }
    @Test fun `truncated stream fails and explicit quota is classified without retry`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n"))
            val provider = SubscriptionProvider(server.url("/v1/"))
            assertThrows(IllegalStateException::class.java) { provider.respond("t", "m", "i", "x") }
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":"insufficient_quota"}}"""))
            assertThrows(QuotaExceeded::class.java) { provider.respond("t", "m", "i", "x") }
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun `temporary rate limit retries after Retry-After`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setBody("""{"models":[{"visibility":"list","slug":"model"}]}"""))
            assertEquals(listOf("model"), SubscriptionProvider(server.url("/v1/")).models("token"))
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun `rate limit retries are bounded and long waits are not shortened`() {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0")) }
            assertThrows(AiRateLimited::class.java) { SubscriptionProvider(server.url("/v1/")).models("token") }
            assertEquals(3, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "301"))
            assertThrows(AiRateLimited::class.java) { SubscriptionProvider(server.url("/v1/")).models("token") }
            assertEquals(4, server.requestCount)
        }
    }
    @Test fun `cancellation interrupts rate limit wait without a second request`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "5"))
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit {
                    assertThrows(java.io.IOException::class.java) {
                        SubscriptionProvider(server.url("/v1/")).models("token") { call ->
                            Thread { Thread.sleep(150); call.cancel() }.start()
                        }
                    }
                }
                future.get(3, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals(1, server.requestCount)
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun `stream rate limit after partial output never retries`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\ndata: {\"type\":\"error\",\"error\":{\"code\":\"rate_limit_exceeded\"}}\n\n"))
            assertThrows(AiRateLimited::class.java) { SubscriptionProvider(server.url("/v1/")).respond("t", "m", "i", "x") }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `API errors distinguish denied access, bad requests and service failures`() {
        MockWebServer().use { server ->
            val provider = SubscriptionProvider(server.url("/v1/"))
            server.enqueue(MockResponse().setResponseCode(403))
            assertThrows(AccountEntitlementDenied::class.java) { provider.models("token") }
            server.enqueue(MockResponse().setResponseCode(400))
            assertThrows(AiRequestRejected::class.java) { provider.models("token") }
            server.enqueue(MockResponse().setResponseCode(503))
            assertThrows(AiServiceUnavailable::class.java) { provider.models("token") }
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun `a 401 refreshes credentials and repeats the rejected API call`() {
        MockWebServer().use { server ->
            var saved: String? = org.json.JSONObject().put("access_token", "old").put("refresh_token", "r1")
                .put("client_id", "client").put("subject", "subject").put("expires_at", System.currentTimeMillis() + 3600000).toString()
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("""{"access_token":"new","refresh_token":"r2","expires_in":3600}"""))
            server.enqueue(MockResponse().setBody("""{"models":[{"visibility":"list","slug":"model"}]}"""))
            val session = AuthSession({ saved }, { saved = it }, server.url("/"))
            val provider = SubscriptionProvider(server.url("/v1/"))
            assertEquals(listOf("model"), session.withAccessToken { provider.models(it) })
            assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
            assertEquals("/api/accounts/oauth/token", server.takeRequest().path)
            assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
            assertEquals(3, server.requestCount)
        }
    }

}
