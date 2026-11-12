package dev.distillpod.android.ai

import android.content.Context
import dev.distillpod.android.AuthSession
import dev.distillpod.android.SubscriptionProvider
import dev.distillpod.android.TokenVault
import dev.distillpod.core.LoopbackCallbackServer
import dev.distillpod.core.OAuthAttempt
import dev.distillpod.core.pkceChallenge
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

data class AccountState(val connected: Boolean = false, val busy: Boolean = false, val message: String = "Connect ChatGPT for quotes, insights and episode chat.", val retry: Boolean = false, val models: List<String> = emptyList())

/** Owns authorization independently of Activity instances. Pending PKCE state stays encrypted. */
class AccountRepository(private val context: Context, private val scope: CoroutineScope) {
    private val vault = TokenVault(context)
    private val pendingVault = TokenVault(context, "chatgpt-pending", "distillpod-pending")
    private val mutable = MutableStateFlow(AccountState(connected = runCatching { vault.read() != null }.getOrDefault(false)))
    val session = AuthSession(vault::read, vault::write, onCredentialsChanged = { connected ->
        mutable.update { it.copy(connected = connected, models = if (connected) it.models else emptyList(), message = if (connected) it.message else "Reconnect your ChatGPT account to continue.") }
    })
    val state: StateFlow<AccountState> = mutable.asStateFlow()
    val browser = MutableStateFlow<String?>(null)
    private val foreground = MutableStateFlow(false)
    private var operation: Job? = null
    private var server: LoopbackCallbackServer? = null
    private var pendingAttempt: OAuthAttempt? = null
    private var pendingCallback: String? = null
    private var pendingCreatedAt = 0L
    private val generation = AtomicLong()

    init { operation = scope.launch(Dispatchers.IO) { restorePending() } }
    fun foreground(value: Boolean) { foreground.value = value }
    fun browserOpened() { browser.value = null }

    fun connect() {
        if (mutable.value.busy) return
        generation.incrementAndGet()
        operation?.cancel(); server?.close()
        operation = scope.launch(Dispatchers.IO) {
            var owned: LoopbackCallbackServer? = null
            mutable.update { it.copy(busy = true, retry = false, message = "Finish sign-in in the browser, then return to DistillPod.") }
            try {
                session.clearPendingConnection()
                pendingCreatedAt = System.currentTimeMillis()
                val listener = LoopbackCallbackServer(); owned = listener; server = listener
                val saved = vault.read()?.let(::JSONObject)
                val clientId = saved?.optString("client_id")?.takeIf { it.isNotBlank() && it != "dynamic_agent_client" }
                val attempt = OAuthAttempt(random(), random(), random(), listener.redirectUri, clientId)
                pendingAttempt = attempt; pendingCallback = null; savePending(attempt, null)
                val prefs = context.getSharedPreferences("installation", Context.MODE_PRIVATE)
                val host = prefs.getString("host", null) ?: "urn:uuid:${UUID.randomUUID()}".also { check(prefs.edit().putString("host", it).commit()) }
                val url = "https://auth.openai.com/api/accounts/authorize".toHttpUrl().newBuilder()
                    .addQueryParameter("client_id", clientId ?: "dynamic_agent_client")
                    .apply { if (clientId == null) addQueryParameter("agent_name_hint", "DistillPod") }
                    .addQueryParameter("ext_agent_host_id", host).addQueryParameter("response_type", "code")
                    .addQueryParameter("redirect_uri", attempt.redirectUri)
                    .addQueryParameter("scope", "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")
                    .addQueryParameter("resource", "https://api.openai.com/v1").addQueryParameter("state", attempt.state)
                    .addQueryParameter("nonce", attempt.nonce).addQueryParameter("code_challenge_method", "S256")
                    .addQueryParameter("code_challenge", pkceChallenge(attempt.verifier)).build()
                browser.value = url.toString()
                val callback = listener.use { it.awaitCallback(attempt) }
                server = null; pendingCallback = callback; savePending(attempt, callback)
                finish(attempt, callback)
            } catch (error: Exception) { if (currentCoroutineContext().isActive) fail(error) }
            finally { owned?.close(); if (server === owned) server = null }
        }
    }

    fun cancel() {
        val epoch = generation.incrementAndGet()
        server?.close(); operation?.cancel(); browser.value = null
        pendingAttempt = null; pendingCallback = null
        scope.launch(Dispatchers.IO) { if (generation.get() == epoch) { pendingVault.write(null); session.clearPendingConnection() } }
        mutable.update { it.copy(busy = false, retry = false, message = "Sign-in cancelled.") }
    }

    fun retry() {
        if (mutable.value.busy) return
        operation = scope.launch(Dispatchers.IO) {
            val attempt = pendingAttempt
            val callback = pendingCallback
            if (attempt == null || callback == null) { mutable.update { it.copy(retry = false, message = "Start a new ChatGPT sign-in.") }; return@launch }
            mutable.update { it.copy(busy = true, message = "Finishing account connection…") }
            try {
                val job = currentCoroutineContext().job
                markExchanging()
                if (session.canRetryConnection()) session.retryConnection { job.isActive } else session.exchange(attempt, callback) { job.isActive }
                currentCoroutineContext().ensureActive()
                connected()
            } catch (error: Exception) { if (currentCoroutineContext().isActive) fail(error) }
        }
    }

    fun disconnect() {
        cancel()
        mutable.update { it.copy(busy = true, message = "Disconnecting…") }
        scope.launch(Dispatchers.IO) { session.disconnect(); mutable.value = AccountState(message = "Account disconnected. Playback and saved content remain available.") }
    }

    fun refreshModels() { val epoch = generation.get(); scope.launch(Dispatchers.IO) {
        try {
            val models = session.withAccessToken { SubscriptionProvider().models(it) }
            if (generation.get() == epoch) mutable.update { if (it.connected) it.copy(models = models, message = "Connected. AI requests use your ChatGPT plan allowance.") else it }
        } catch (error: Exception) { if (generation.get() == epoch) mutable.update { it.copy(
            message = when (error) {
                is dev.distillpod.android.AccountEntitlementDenied -> "Your ChatGPT account does not have access to this feature."
                is dev.distillpod.android.AiRequestRejected, is dev.distillpod.android.AiServiceUnavailable -> error.message ?: "Could not refresh model access."
                is dev.distillpod.android.ReauthorizationRequired -> "Reconnect your ChatGPT account to continue."
                else -> "Could not refresh model access. Check your connection and retry."
            }) } }
    } }

    private suspend fun finish(attempt: OAuthAttempt, callback: String) {
        mutable.update { it.copy(message = "Callback received. Return to DistillPod to finish connecting.") }
        foreground.first { it }
        val job = currentCoroutineContext().job
        markExchanging()
        session.exchange(attempt, callback) { job.isActive }
        currentCoroutineContext().ensureActive()
        connected()
    }

    private fun connected() {
        pendingVault.write(null); pendingAttempt = null; pendingCallback = null
        mutable.update { it.copy(connected = true, busy = false, retry = false, message = "Connected. AI requests use your ChatGPT plan allowance.") }
        refreshModels()
    }

    private fun fail(error: Exception) {
        if (error is CancellationException || error is java.net.SocketException && operation?.isCancelled == true) return
        val retry = session.canRetryConnection()
        if (!retry) { pendingVault.write(null); pendingAttempt = null; pendingCallback = null }
        mutable.update { it.copy(busy = false, retry = retry, message = when {
            retry -> "Could not finish connecting. Check your network, then retry."
            error is dev.distillpod.core.ConsentDenied -> "Plan access was declined. You can continue listening without AI."
            error is java.net.SocketTimeoutException -> "Sign-in expired. Start a fresh attempt."
            else -> "Sign-in could not finish. Check the connection and try again."
        }) }
    }

    private suspend fun restorePending() {
        var owned: LoopbackCallbackServer? = null
        try {
            val json = pendingVault.read()?.let(::JSONObject) ?: return
            currentCoroutineContext().ensureActive()
            if (System.currentTimeMillis() - json.getLong("createdAt") > 600_000) { pendingVault.write(null); return }
            if (json.optString("phase") == "exchanging") {
                pendingVault.write(null)
                mutable.update { it.copy(message = "The app stopped during account exchange. Start a fresh sign-in.") }
                return
            }
            val attempt = OAuthAttempt(json.getString("state"), json.getString("nonce"), json.getString("verifier"), json.getString("redirectUri"), json.optString("clientId").takeIf(String::isNotBlank))
            pendingAttempt = attempt; pendingCreatedAt = json.getLong("createdAt")
            pendingCallback = json.optString("callback").takeIf(String::isNotBlank)
            mutable.update { it.copy(busy = true, message = "Recovering the pending account connection…") }
            if (pendingCallback != null) finish(attempt, pendingCallback!!) else {
                val remaining = (600_000 - (System.currentTimeMillis() - pendingCreatedAt)).coerceAtLeast(1)
                val listener = LoopbackCallbackServer(timeoutMs = remaining, port = URI(attempt.redirectUri).port); owned = listener; server = listener
                check(listener.redirectUri == attempt.redirectUri)
                val callback = listener.use { it.awaitCallback(attempt) }
                server = null; pendingCallback = callback; savePending(attempt, callback)
                finish(attempt, callback)
            }
        } catch (error: Exception) { if (currentCoroutineContext().isActive) fail(error) }
        finally { owned?.close(); if (server === owned) server = null }
    }

    private fun savePending(attempt: OAuthAttempt, callback: String?) {
        pendingVault.write(JSONObject().put("state", attempt.state).put("nonce", attempt.nonce).put("verifier", attempt.verifier)
            .put("redirectUri", attempt.redirectUri).put("clientId", attempt.clientId ?: "").put("callback", callback ?: "").put("createdAt", pendingCreatedAt).put("phase", if (callback == null) "listening" else "received").toString())
    }
    private fun markExchanging() { pendingVault.read()?.let { pendingVault.write(JSONObject(it).put("phase", "exchanging").toString()) } }
    private fun random(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
}
