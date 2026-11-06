package dev.distillpod.android.diagnostics

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DiagnosticDeliveryTest {
    @Test fun `acknowledgement retry and rejection are distinguished without redirects`() {
        MockWebServer().use { server ->
            server.start()
            val delivery=DiagnosticDelivery(OkHttpClient.Builder().followRedirects(false).build())
            val endpoint=server.url("/api/1/envelope/").toString()
            server.enqueue(MockResponse().setResponseCode(200))
            assertEquals(DiagnosticDelivery.Outcome.ACCEPTED,delivery.send(endpoint,"public-key","fixture".toByteArray()).outcome)
            val request=server.takeRequest()
            assertEquals("application/x-sentry-envelope",request.getHeader("Content-Type"))
            assertEquals("fixture",request.body.readUtf8())
            assertNull(request.getHeader("Authorization"))
            for(code in listOf(500,401,403,408,429)) {
                server.enqueue(MockResponse().setResponseCode(code).setHeader("Retry-After","120"))
                val receipt=delivery.send(endpoint,"public-key",byteArrayOf())
                assertEquals(DiagnosticDelivery.Outcome.RETRY,receipt.outcome)
                if(code==429)assertEquals(120L,receipt.retrySeconds)
            }
            server.enqueue(MockResponse().setResponseCode(200).setHeader("X-Sentry-Rate-Limits","60:log:organization"))
            val limited=delivery.send(endpoint,"public-key",byteArrayOf())
            assertEquals(DiagnosticDelivery.Outcome.ACCEPTED,limited.outcome);assertEquals(60L,limited.retrySeconds)
            server.enqueue(MockResponse().setResponseCode(400))
            assertEquals(DiagnosticDelivery.Outcome.REJECTED,delivery.send(endpoint,"public-key",byteArrayOf()).outcome)
        }
    }
}
