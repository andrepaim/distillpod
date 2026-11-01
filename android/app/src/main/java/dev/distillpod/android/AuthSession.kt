package dev.distillpod.android
import com.nimbusds.jose.jwk.JWKSet
import dev.distillpod.core.OAuthAttempt
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** All credential mutation and refresh is serialized within the app process. */
class AuthSession(private val read: () -> String?, private val write: (String?) -> Unit,
                  private val authBase: HttpUrl = "https://auth.openai.com/".toHttpUrl(), private val http: OkHttpClient = OkHttpClient()) {
    @Synchronized fun exchange(attempt: OAuthAttempt, callbackUrl: String) {
        val callback = attempt.callback(callbackUrl)
        val response = token(FormBody.Builder().add("grant_type", "authorization_code").add("client_id", callback.clientId)
            .add("code", callback.code).add("code_verifier", attempt.verifier).add("redirect_uri", attempt.redirectUri)
            .add("resource", "https://api.openai.com/v1").build())
        require(response.optString("scope").split(' ').contains("chatgpt.tokens.use.direct")) { "ChatGPT plan usage was not authorized" }
        val keysRequest = Request.Builder().url(authBase.resolve(".well-known/jwks.json")!!).build()
        val keys = http.newCall(keysRequest).execute().use { r -> check(r.isSuccessful); JWKSet.parse(r.body!!.string()) }
        val identity = verifyIdentity(response.getString("id_token"), keys, callback.clientId, attempt.nonce)
        response.put("client_id", callback.clientId).put("subject", identity.subject).put("expires_at", System.currentTimeMillis() + response.getLong("expires_in") * 1000)
        write(response.toString())
    }
    @Synchronized fun accessToken(): String {
        val saved = JSONObject(read() ?: throw ReauthorizationRequired())
        if (saved.getLong("expires_at") <= System.currentTimeMillis() + 30000) {
            val renewed = try {
                token(FormBody.Builder().add("grant_type", "refresh_token").add("client_id", saved.getString("client_id"))
                    .add("refresh_token", saved.getString("refresh_token")).add("resource", "https://api.openai.com/v1").build())
            } catch (e: ReauthorizationRequired) {
                write(null)
                throw e
            }
            if(renewed.has("scope")) require(renewed.getString("scope").split(' ').contains("chatgpt.tokens.use.direct"))
            saved.put("access_token", renewed.getString("access_token"))
            if (renewed.has("refresh_token")) saved.put("refresh_token", renewed.getString("refresh_token"))
            saved.put("expires_at", System.currentTimeMillis() + renewed.getLong("expires_in") * 1000)
            write(saved.toString())
        }
        return saved.getString("access_token")
    }
    @Synchronized fun disconnect() { write(null) }
    private fun token(body: FormBody): JSONObject = http.newCall(Request.Builder().url(authBase.resolve("api/accounts/oauth/token")!!).post(body).build()).execute().use { r ->
        if(r.code == 401 || r.code == 403) throw ReauthorizationRequired()
        check(r.isSuccessful) { "Account connection failed (${r.code})" }
        JSONObject(r.body!!.string())
    }
}
