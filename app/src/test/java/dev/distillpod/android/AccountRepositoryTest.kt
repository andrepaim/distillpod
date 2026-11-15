package dev.distillpod.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.*
import dev.distillpod.android.ai.AccountRepository
import java.util.Date
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountRepositoryTest {
    /** Behaves like a vault whose Keystore key was invalidated: the old blob cannot be opened, a new write succeeds. */
    private class Store(var value: String?, var unreadable: Boolean = false) : CredentialStore {
        override fun read(): String? = if (unreadable) throw AEADBadTagException("invalidated key") else value
        override fun write(value: String?) { this.value = value; unreadable = false }
    }

    @Test fun `connect replaces a credential vault that can no longer be opened`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val other = context.getSharedPreferences("unrelated-app-data", Context.MODE_PRIVATE).apply { edit().putString("kept", "yes").commit() }
        val vault = Store("old-sealed-blob", unreadable = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        MockWebServer().use { server ->
            try {
                val repo = AccountRepository(context, scope, vault, Store(null), server.url("/"), { listOf("gpt-test") })
                assertFalse(repo.state.value.connected)
                assertTrue(repo.state.value.message, repo.state.value.message.contains("Continue with ChatGPT to replace it"))
                repo.foreground(true)
                repo.connect()
                val authorize = withTimeout(10_000) { repo.browser.filterNotNull().first() }.toHttpUrl()
                assertEquals("dynamic_agent_client", authorize.queryParameter("client_id"))
                val key = RSAKeyGenerator(2048).keyID("key").generate()
                val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("key").build(), JWTClaimsSet.Builder()
                    .issuer("https://auth.openai.com").subject("account").audience("issued").claim("nonce", authorize.queryParameter("nonce"))
                    .expirationTime(Date(System.currentTimeMillis() + 60_000)).build())
                jwt.sign(RSASSASigner(key))
                server.enqueue(MockResponse().setBody(JSONObject().put("access_token", "access").put("refresh_token", "refresh").put("id_token", jwt.serialize())
                    .put("expires_in", 3600).put("scope", "chatgpt.tokens.use.direct").toString()))
                server.enqueue(MockResponse().setBody(JWKSet(key.toPublicJWK()).toString()))
                val callback = authorize.queryParameter("redirect_uri")!!.toHttpUrl().newBuilder()
                    .addQueryParameter("state", authorize.queryParameter("state")).addQueryParameter("code", "code").addQueryParameter("client_id", "issued").build()
                OkHttpClient().newCall(Request.Builder().url(callback).build()).execute().close()
                val connected = withTimeout(10_000) { repo.state.first { it.connected && !it.busy && it.models.isNotEmpty() } }
                assertEquals(listOf("gpt-test"), connected.models)
                val saved = JSONObject(vault.read()!!)
                assertEquals("account", saved.getString("subject"))
                assertEquals("issued", saved.getString("client_id"))
                assertEquals("yes", other.getString("kept", null))
            } finally { scope.cancel() }
        }
    }
}
