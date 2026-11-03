package dev.distillpod.android

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.distillpod.core.OAuthAttempt
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionRetryTest {
    private val key = RSAKeyGenerator(2048).keyID("test").generate()
    private val attempt = OAuthAttempt("state", "nonce", "verifier", "http://127.0.0.1:1455/auth/callback")
    private val callback = attempt.redirectUri + "?state=state&code=public-test-code&client_id=issued"
    private fun tokenResponse(): String {
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), JWTClaimsSet.Builder()
            .issuer("https://auth.openai.com").subject("test-account").audience("issued").claim("nonce", "nonce")
            .expirationTime(Date(System.currentTimeMillis() + 60000)).build())
        jwt.sign(RSASSASigner(key))
        return JSONObject().put("access_token", "test-access").put("refresh_token", "test-refresh")
            .put("id_token", jwt.serialize()).put("scope", "chatgpt.tokens.use.direct").put("expires_in", 3600).toString()
    }

    @Test fun `DNS failure keeps the validated callback for a controlled retry`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(tokenResponse()))
            server.enqueue(MockResponse().setBody(JWKSet(key.toPublicJWK()).toString()))
            val lookups = AtomicInteger()
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (lookups.getAndIncrement() == 0) throw UnknownHostException("simulated DNS interruption")
                    return listOf(InetAddress.getByName("127.0.0.1"))
                }
            }).build()
            var saved: String? = null
            val auth = AuthSession({ saved }, { saved = it }, server.url("/").newBuilder().host("auth.test").build(), client)
            assertThrows(UnknownHostException::class.java) { auth.exchange(attempt, callback) }
            assertTrue(auth.canRetryConnection()); assertNull(saved)
            auth.retryConnection()
            assertFalse(auth.canRetryConnection())
            assertEquals("test-access", auth.accessToken())
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `verification retry reuses received tokens without exchanging the code twice`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(tokenResponse()))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setBody(JWKSet(key.toPublicJWK()).toString()))
            var saved: String? = null
            val issuedAt = System.currentTimeMillis()
            val now = AtomicLong(issuedAt)
            val auth = AuthSession({ saved }, { saved = it }, server.url("/"), OkHttpClient.Builder().retryOnConnectionFailure(false).build(), now::get)
            assertThrows(IOException::class.java) { auth.exchange(attempt, callback) }
            assertTrue(auth.canRetryConnection()); assertNull(saved)
            now.addAndGet(5000)
            auth.retryConnection()
            assertEquals("test-access", auth.accessToken())
            assertEquals(issuedAt + 3_600_000L, JSONObject(saved!!).getLong("expires_at"))
            assertEquals("POST", server.takeRequest().method)
            assertEquals("/.well-known/jwks.json", server.takeRequest().path)
            assertEquals("/.well-known/jwks.json", server.takeRequest().path)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun `ambiguous token transport failure requires fresh authorization`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            var saved: String? = null
            val auth = AuthSession({ saved }, { saved = it }, server.url("/"), OkHttpClient.Builder().retryOnConnectionFailure(false).build())
            assertThrows(IOException::class.java) { auth.exchange(attempt, callback) }
            assertFalse(auth.canRetryConnection()); assertNull(saved)
        }
    }

    @Test fun `invalid plan scope is terminal and never becomes a saved connection`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"access_token":"test","scope":"openid"}"""))
            var saved: String? = null
            val auth = AuthSession({ saved }, { saved = it }, server.url("/"))
            assertThrows(IllegalArgumentException::class.java) { auth.exchange(attempt, callback) }
            assertFalse(auth.canRetryConnection()); assertNull(saved)
        }
    }

    @Test fun `disconnect discards pending authorization`() {
        MockWebServer().use { server ->
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException("simulated")
            }).build()
            val auth = AuthSession({ null }, {}, server.url("/").newBuilder().host("auth.test").build(), client)
            assertThrows(UnknownHostException::class.java) { auth.exchange(attempt, callback) }
            assertTrue(auth.canRetryConnection())
            auth.disconnect()
            assertFalse(auth.canRetryConnection())
        }
    }
    @Test fun `cancelled exchange cannot save received credentials`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(tokenResponse()))
            server.enqueue(MockResponse().setBody(JWKSet(key.toPublicJWK()).toString()))
            var saved: String? = null
            val auth = AuthSession({ saved }, { saved = it }, server.url("/"))
            assertThrows(java.util.concurrent.CancellationException::class.java) { auth.exchange(attempt, callback) { false } }
            assertNull(saved)
        }
    }
    @Test fun `returning registration cannot replace a different saved identity`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(tokenResponse()))
            server.enqueue(MockResponse().setBody(JWKSet(key.toPublicJWK()).toString()))
            val original = """{"client_id":"issued","subject":"different-account"}"""
            var saved: String? = original
            val auth = AuthSession({ saved }, { saved = it }, server.url("/"))
            assertThrows(IllegalArgumentException::class.java) { auth.exchange(attempt.copy(clientId = "issued"), callback) }
            assertEquals(original, saved)
        }
    }
}
