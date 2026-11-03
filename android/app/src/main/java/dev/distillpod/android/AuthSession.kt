package dev.distillpod.android
import com.nimbusds.jose.jwk.JWKSet
import dev.distillpod.core.OAuthAttempt
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.UnknownHostException

/** All credential mutation and refresh is serialized within the app process. */
class AuthSession(private val read: () -> String?, private val write: (String?) -> Unit,
                  private val authBase: HttpUrl = "https://auth.openai.com/".toHttpUrl(), private val http: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
                  private val clock: () -> Long = System::currentTimeMillis) {
    private data class PendingExchange(val attempt: OAuthAttempt, val callbackUrl: String, var tokens: JSONObject? = null)
    private var pending: PendingExchange? = null
    @Synchronized fun canRetryConnection(): Boolean = pending != null
    @Synchronized fun clearPendingConnection() { pending = null }
    @Synchronized fun retryConnection(canCommit: () -> Boolean = { true }) {
        val retry = checkNotNull(pending) { "Start a new ChatGPT sign-in" }
        exchange(retry.attempt, retry.callbackUrl, canCommit)
    }
    @Synchronized fun exchange(attempt: OAuthAttempt, callbackUrl: String, canCommit: () -> Boolean = { true }) {
        val callback = attempt.callback(callbackUrl)
        val current = pending?.takeIf { it.attempt == attempt && it.callbackUrl == callbackUrl }
            ?: PendingExchange(attempt, callbackUrl)
        pending = current
        try {
            val response = current.tokens ?: token(FormBody.Builder().add("grant_type", "authorization_code").add("client_id", callback.clientId)
                .add("code", callback.code).add("code_verifier", attempt.verifier).add("redirect_uri", attempt.redirectUri)
                .add("resource", "https://api.openai.com/v1").build()).also {
                    require(it.optString("scope").split(' ').contains("chatgpt.tokens.use.direct")) { "ChatGPT plan usage was not authorized" }
                    it.put("expires_at", clock() + it.getLong("expires_in") * 1000)
                    current.tokens = it
                }
            require(response.optString("scope").split(' ').contains("chatgpt.tokens.use.direct")) { "ChatGPT plan usage was not authorized" }
            val keysRequest = Request.Builder().url(authBase.resolve(".well-known/jwks.json")!!).build()
            val keys = http.newCall(keysRequest).execute().use { r -> check(r.isSuccessful); JWKSet.parse(r.body!!.string()) }
            val identity = verifyIdentity(response.getString("id_token"), keys, callback.clientId, attempt.nonce)
            if (attempt.clientId != null) {
                val original = read()?.let(::JSONObject)
                if (original != null && original.optString("client_id") == attempt.clientId) {
                    require(original.getString("subject") == identity.subject) { "Sign in with the previously connected ChatGPT account" }
                }
            }
            response.put("client_id", callback.clientId).put("subject", identity.subject)
            if (!canCommit()) throw java.util.concurrent.CancellationException("Sign-in cancelled")
            write(response.toString())
            pending = null
        } catch (error: Exception) {
            // DNS failure means the token request was not sent. Once tokens were received,
            // retry only identity verification; never exchange a consumed code again.
            if (error !is UnknownHostException && !(current.tokens != null && error is IOException)) pending = null
            throw error
        }
    }
    @Synchronized fun accessToken(forceRefresh: Boolean = false): String {
        val saved = JSONObject(read() ?: throw ReauthorizationRequired())
        if (forceRefresh || saved.getLong("expires_at") <= clock() + 30000) {
            val renewed = try {
                token(FormBody.Builder().add("grant_type", "refresh_token").add("client_id", saved.getString("client_id"))
                    .add("refresh_token", saved.getString("refresh_token")).add("resource", "https://api.openai.com/v1").build())
            } catch (e: ReauthorizationRequired) {
                write(null)
                throw e
            }
            if (renewed.has("scope")) {
                if (!renewed.getString("scope").split(' ').contains("chatgpt.tokens.use.direct")) { write(null); throw ReauthorizationRequired() }
                saved.put("scope", renewed.getString("scope"))
            }
            saved.put("access_token", renewed.getString("access_token"))
            if (renewed.has("refresh_token")) saved.put("refresh_token", renewed.getString("refresh_token"))
            saved.put("expires_at", clock() + renewed.getLong("expires_in") * 1000)
            write(saved.toString())
        }
        return saved.getString("access_token")
    }
    @Synchronized fun disconnect() { pending = null; write(null) }
    private fun token(body: FormBody): JSONObject = http.newCall(Request.Builder().url(authBase.resolve("api/accounts/oauth/token")!!).post(body).build()).execute().use { r ->
        if(r.code == 401 || r.code == 403) throw ReauthorizationRequired()
        if (r.code == 400) {
            val error = runCatching { JSONObject(r.body!!.string()).optString("error") }.getOrDefault("")
            if (error in listOf("invalid_grant", "invalid_client")) throw ReauthorizationRequired()
        }
        check(r.isSuccessful) { "Account connection failed (${r.code})" }
        JSONObject(r.body!!.string())
    }
}
