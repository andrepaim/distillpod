package dev.distillpod.android
import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.*
import dev.distillpod.core.OAuthAttempt
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthSessionTest {
    @Test fun `token exchange verifies identity before saving credentials`() {
        val key = RSAKeyGenerator(2048).keyID("key").generate()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("key").build(), JWTClaimsSet.Builder()
            .issuer("https://auth.openai.com").subject("account").audience("issued").claim("nonce", "nonce").expirationTime(Date(System.currentTimeMillis() + 60000)).build())
        jwt.sign(RSASSASigner(key))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(JSONObject().put("access_token", "access").put("refresh_token", "refresh").put("id_token", jwt.serialize())
                .put("expires_in", 3600).put("scope", "chatgpt.tokens.use.direct").toString()))
            server.enqueue(MockResponse().setBody(JWKSet(key.toPublicJWK()).toString()))
            var saved: String? = null
            val auth = AuthSession({ saved }, { saved = it }, server.url("/"))
            val p = OAuthAttempt("state", "nonce", "verifier", "http://127.0.0.1:1455/auth/callback")
            auth.exchange(p, "http://127.0.0.1:1455/auth/callback?state=state&code=code&client_id=issued")
            assertEquals("access", auth.accessToken())
            assertEquals("account", JSONObject(saved!!).getString("subject"))
            val request = server.takeRequest()
            assertTrue(request.body.readUtf8().contains("code_verifier=verifier"))
        }
    }
    @Test fun `signing in without plan permission never saves a usable session`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"access_token":"access","scope":"openid"}"""))
            var saved: String? = null
            val auth = AuthSession({ saved }, { saved = it }, server.url("/"))
            val p = OAuthAttempt("s", "n", "v", "http://127.0.0.1:1455/auth/callback")
            assertThrows(IllegalArgumentException::class.java) { auth.exchange(p, "http://127.0.0.1:1455/auth/callback?state=s&code=c&client_id=i") }
            assertNull(saved)
        }
    }
}
