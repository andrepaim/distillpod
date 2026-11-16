package dev.distillpod.android

import dev.distillpod.android.ai.AiText
import dev.distillpod.android.ai.ChatGptGateway
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #227: a saved model missing from the account catalog must not block AI; the catalog is cached briefly. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatGptGatewayTest {
    private fun credentials(token: String) = JSONObject().put("access_token", token).put("refresh_token", "refresh").put("client_id", "client")
        .put("subject", "account").put("expires_at", Long.MAX_VALUE / 2).toString()
    private fun catalog(vararg slugs: String) = MockResponse().setBody(JSONObject().put("models",
        JSONArray(slugs.map { JSONObject().put("slug", it).put("visibility", "list") })).toString())
    private fun reply(text: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("data: {\"type\":\"response.output_text.delta\",\"delta\":\"$text\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n")
    private fun MockWebServer.paths() = (0 until requestCount).map { takeRequest().let { r -> r.path + if (r.path!!.endsWith("responses")) ":" + JSONObject(r.body.readUtf8()).getString("model") else "" } }

    @Test fun `an unavailable saved model falls back to the first available model`() = runBlocking {
        MockWebServer().use { server ->
            var saved = "retired-model"
            val gateway = ChatGptGateway(AuthSession({ credentials("token") }, { error("No credential write expected") }, server.url("/auth/")),
                { saved }, SubscriptionProvider(server.url("/v1/")), clock = { 0L })
            server.enqueue(catalog("gpt-a", "gpt-b")); server.enqueue(reply("fallback"))
            assertEquals(AiText("fallback", "gpt-a"), gateway.complete("instructions", "input"))
            saved = "gpt-b"; server.enqueue(reply("saved"))
            assertEquals(AiText("saved", "gpt-b"), gateway.complete("instructions", "input"))
            saved = ""; server.enqueue(reply("default"))
            assertEquals(AiText("default", "gpt-a"), gateway.complete("instructions", "input"))
            assertEquals(listOf("/v1/models", "/v1/responses:gpt-a", "/v1/responses:gpt-b", "/v1/responses:gpt-a"), server.paths())
        }
    }

    @Test fun `the catalog is refetched after it expires, after a failed request and when the account changes`() = runBlocking {
        MockWebServer().use { server ->
            var now = 0L; var token = "token"
            val gateway = ChatGptGateway(AuthSession({ credentials(token) }, { error("No credential write expected") }, server.url("/auth/")),
                { "gpt-b" }, SubscriptionProvider(server.url("/v1/")), clock = { now })
            server.enqueue(catalog("gpt-a", "gpt-b")); server.enqueue(reply("one")); server.enqueue(reply("two"))
            gateway.complete("i", "x"); now += 60_000; gateway.complete("i", "x")
            // The plan lost gpt-b after the catalog was cached: the request is rejected and the next one refetches.
            server.enqueue(MockResponse().setResponseCode(400)); server.enqueue(catalog("gpt-a")); server.enqueue(reply("three"))
            assertThrows(AiRequestRejected::class.java) { runBlocking { gateway.complete("i", "x") } }
            assertEquals("gpt-a", gateway.complete("i", "x").model)
            now += ChatGptGateway.CATALOG_TTL_MS + 1
            server.enqueue(catalog("gpt-a", "gpt-b")); server.enqueue(reply("four"))
            assertEquals("gpt-b", gateway.complete("i", "x").model)
            token = "other-account"
            server.enqueue(catalog("gpt-a")); server.enqueue(reply("five"))
            assertEquals("gpt-a", gateway.complete("i", "x").model)
            assertEquals(listOf("/v1/models", "/v1/responses:gpt-b", "/v1/responses:gpt-b", "/v1/responses:gpt-b", "/v1/models", "/v1/responses:gpt-a",
                "/v1/models", "/v1/responses:gpt-b", "/v1/models", "/v1/responses:gpt-a"), server.paths())
        }
    }
}
