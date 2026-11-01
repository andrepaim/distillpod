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
    @Test fun `truncated stream fails and quota is classified without retry`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n"))
            val provider = SubscriptionProvider(server.url("/v1/"))
            assertThrows(IllegalStateException::class.java) { provider.respond("t", "m", "i", "x") }
            server.enqueue(MockResponse().setResponseCode(429))
            assertThrows(QuotaExceeded::class.java) { provider.respond("t", "m", "i", "x") }
            assertEquals(2, server.requestCount)
        }
    }
}
