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

    private class Started(val repo: AccountRepository, val callback: okhttp3.HttpUrl)
    private suspend fun start(context: Context, scope: CoroutineScope, server: MockWebServer, foreground: Boolean): Started {
        val repo = AccountRepository(context, scope, Store(null), Store(null), server.url("/"), { listOf("gpt-test") })
        repo.foreground(foreground)
        repo.connect()
        val authorize = withTimeout(10_000) { repo.browser.filterNotNull().first() }.toHttpUrl()
        val callback = authorize.queryParameter("redirect_uri")!!.toHttpUrl().newBuilder()
            .addQueryParameter("state", authorize.queryParameter("state")).addQueryParameter("code", "code").addQueryParameter("client_id", "issued").build()
        return Started(repo, callback)
    }
    private fun browserGet(url: okhttp3.HttpUrl): Pair<Int, String> =
        OkHttpClient.Builder().retryOnConnectionFailure(false).build().newCall(Request.Builder().url(url).build()).execute().use { it.code to it.body!!.string() }
    private fun assertPortClosed(url: okhttp3.HttpUrl) {
        val until = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < until) {
            try { java.net.Socket("127.0.0.1", url.port).close(); Thread.sleep(20) } catch (_: java.net.ConnectException) { return }
        }
        fail("The callback listener is still open")
    }

    @Test fun `the browser can request the callback again while the app waits to finish`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        MockWebServer().use { server ->
            try {
                val started = start(context, scope, server, foreground = false)
                val first = browserGet(started.callback)
                assertEquals(200, first.first)
                withTimeout(10_000) { started.repo.state.first { it.message.startsWith("Callback received") } }
                // A browser preload or retry of the same redirect must see the same page, not a refused connection.
                assertEquals(first, browserGet(started.callback))
                started.repo.cancel()
                assertPortClosed(started.callback)
            } finally { scope.cancel() }
        }
    }

    @Test fun `an expired or rejected authorization code asks for a fresh attempt`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        MockWebServer().use { server ->
            try {
                server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"Authorization code expired"}"""))
                val started = start(context, scope, server, foreground = true)
                assertEquals(200, browserGet(started.callback).first)
                val done = withTimeout(10_000) { started.repo.state.first { !it.busy } }
                assertEquals("Sign-in expired. Start a fresh attempt.", done.message)
                assertFalse(done.retry); assertFalse(done.connected)
                assertPortClosed(started.callback)
            } finally { scope.cancel() }
        }
    }

    @Test fun `a token server failure keeps the connection message`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        MockWebServer().use { server ->
            try {
                server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"temporarily_unavailable"}"""))
                val started = start(context, scope, server, foreground = true)
                assertEquals(200, browserGet(started.callback).first)
                val done = withTimeout(10_000) { started.repo.state.first { !it.busy } }
                assertEquals("Sign-in could not finish. Check the connection and try again.", done.message)
            } finally { scope.cancel() }
        }
    }
}
