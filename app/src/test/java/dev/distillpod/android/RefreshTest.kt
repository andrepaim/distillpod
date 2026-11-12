package dev.distillpod.android
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RefreshTest {
    private fun expired() = """{"access_token":"old","refresh_token":"r1","client_id":"issued","subject":"account","expires_at":0}"""
    @Test fun `rotated token pair is saved and reused after refresh`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"access_token":"new","refresh_token":"r2","expires_in":3600,"scope":"chatgpt.tokens.use.direct"}"""))
            var saved: String? = expired()
            val session = AuthSession({ saved }, { saved = it }, server.url("/"))
            assertEquals("new", session.accessToken())
            assertEquals("r2", JSONObject(saved!!).getString("refresh_token"))
            assertEquals("new", session.accessToken()); assertEquals(1, server.requestCount)
        }
    }
    @Test fun `revoked refresh clears unusable credentials`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
            var saved: String? = expired()
            val session = AuthSession({ saved }, { saved = it }, server.url("/"))
            assertThrows(ReauthorizationRequired::class.java) { session.accessToken() }
            assertNull(saved)
        }
    }
    @Test fun `invalid grant and removed plan scope have distinct credential behavior`() {
        listOf(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""),
            MockResponse().setBody("""{"access_token":"new","expires_in":3600,"scope":"openid"}""")).forEach { response ->
            MockWebServer().use { server ->
                server.enqueue(response)
                var saved: String? = expired()
                val session = AuthSession({ saved }, { saved = it }, server.url("/"))
                if (response.status.contains("400")) {
                    assertThrows(ReauthorizationRequired::class.java) { session.accessToken() }
                    assertNull(saved)
                } else {
                    assertThrows(AccountEntitlementDenied::class.java) { session.accessToken() }
                    assertNotNull(saved)
                }
                assertEquals(1, server.requestCount)
            }
        }
    }
    @Test fun `temporary forbidden token response keeps stored credentials`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            var saved: String? = expired()
            val session = AuthSession({ saved }, { saved = it }, server.url("/"))
            assertThrows(IllegalStateException::class.java) { session.accessToken() }
            assertEquals("r1", JSONObject(saved!!).getString("refresh_token"))
        }
    }
    @Test fun `stale invalid grant cannot erase a newer stored refresh token`() {
        MockWebServer().use { server ->
            val saved = java.util.concurrent.atomic.AtomicReference<String?>(expired())
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    saved.set(JSONObject(expired()).put("refresh_token", "rotated").toString())
                    return MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
                }
            }
            val session = AuthSession({ saved.get() }, { saved.set(it) }, server.url("/"))
            val error = assertThrows(IllegalStateException::class.java) { session.accessToken() }
            assertFalse(error is ReauthorizationRequired)
            assertEquals("rotated", JSONObject(saved.get()!!).getString("refresh_token"))
        }
    }

    @Test fun `concurrent calls share one rotated refresh`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"access_token":"new","refresh_token":"r2","expires_in":3600}"""))
            val saved = java.util.concurrent.atomic.AtomicReference<String?>(expired())
            val session = AuthSession({ saved.get() }, { saved.set(it) }, server.url("/"))
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val start = java.util.concurrent.CountDownLatch(1)
                val calls = List(2) { pool.submit<String> { start.await(); session.accessToken() } }
                start.countDown()
                assertEquals(listOf("new", "new"), calls.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) })
                assertEquals(1, server.requestCount)
                assertEquals("r2", JSONObject(saved.get()!!).getString("refresh_token"))
            } finally { pool.shutdownNow() }
        }
    }
    @Test fun `successful stale refresh does not replace newer credentials`() {
        MockWebServer().use { server ->
            val saved = java.util.concurrent.atomic.AtomicReference<String?>(expired())
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    saved.set(JSONObject(expired()).put("refresh_token", "newer").put("access_token", "newer-access")
                        .put("expires_at", System.currentTimeMillis() + 3600000).toString())
                    return MockResponse().setBody("""{"access_token":"stale","refresh_token":"stale-rotated","expires_in":3600}""")
                }
            }
            val session = AuthSession({ saved.get() }, { saved.set(it) }, server.url("/"))
            assertEquals("newer-access", session.accessToken())
            assertEquals("newer", JSONObject(saved.get()!!).getString("refresh_token"))
        }
    }
    @Test fun `invalid grant notifies account state when it removes current credentials`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
            var saved: String? = expired()
            val states = mutableListOf<Boolean>()
            val session = AuthSession({ saved }, { saved = it }, server.url("/"), onCredentialsChanged = { states.add(it) })
            assertThrows(ReauthorizationRequired::class.java) { session.accessToken() }
            assertNull(saved)
            assertEquals(listOf(false), states)
        }
    }

    @Test fun `unauthorized API call forces one refresh then retries once`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"access_token":"new","refresh_token":"r2","expires_in":3600}"""))
            var saved: String? = JSONObject(expired()).put("expires_at", System.currentTimeMillis() + 3600000).toString()
            val session = AuthSession({ saved }, { saved = it }, server.url("/"))
            val used = mutableListOf<String>()
            assertEquals("success", session.withAccessToken { token ->
                used.add(token)
                if (token == "old") throw ReauthorizationRequired()
                "success"
            })
            assertEquals(listOf("old", "new"), used)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `second unauthorized response is not retried again`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"access_token":"new","expires_in":3600}"""))
            var saved: String? = JSONObject(expired()).put("expires_at", System.currentTimeMillis() + 3600000).toString()
            val session = AuthSession({ saved }, { saved = it }, server.url("/"))
            var calls = 0
            assertThrows(ReauthorizationRequired::class.java) {
                session.withAccessToken<String> { calls++; throw ReauthorizationRequired() }
            }
            assertEquals(2, calls)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `concurrent unauthorized requests perform only one forced refresh`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"access_token":"new","refresh_token":"r2","expires_in":3600}"""))
            val saved = java.util.concurrent.atomic.AtomicReference<String?>(
                JSONObject(expired()).put("expires_at", System.currentTimeMillis() + 3600000).toString())
            val session = AuthSession({ saved.get() }, { saved.set(it) }, server.url("/"))
            val ready = java.util.concurrent.CountDownLatch(2)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val jobs = List(2) { pool.submit<String> {
                    session.withAccessToken { token ->
                        if (token == "old") { ready.countDown(); check(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)); throw ReauthorizationRequired() }
                        token
                    }
                } }
                assertEquals(listOf("new", "new"), jobs.map { it.get(15, java.util.concurrent.TimeUnit.SECONDS) })
                assertEquals(1, server.requestCount)
            } finally { pool.shutdownNow() }
        }
    }

}
