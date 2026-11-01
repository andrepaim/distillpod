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
            server.enqueue(MockResponse().setResponseCode(401))
            var saved: String? = expired()
            val session = AuthSession({ saved }, { saved = it }, server.url("/"))
            assertThrows(ReauthorizationRequired::class.java) { session.accessToken() }
            assertNull(saved)
        }
    }
}
