package dev.distillpod.core
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

fun pkceChallenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
class ConsentDenied : IllegalArgumentException("Plan authorization was declined")
data class OAuthCallback(val code: String, val clientId: String)
data class OAuthAttempt(val state: String, val nonce: String, val verifier: String, val redirectUri: String, val clientId: String? = null) {
    fun callback(url: String): OAuthCallback {
        val actual = URI(url); val expected = URI(redirectUri)
        require(actual.scheme == expected.scheme && actual.host == expected.host && actual.port == expected.port && actual.path == expected.path && actual.userInfo == null && actual.fragment == null) { "Unexpected callback address" }
        val pairs = (actual.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.map { item ->
            val p = item.split('=', limit = 2)
            URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p.getOrElse(1) { "" }, "UTF-8")
        }
        require(pairs.map { it.first }.distinct().size == pairs.size) { "Duplicate callback parameter" }
        val params = pairs.toMap()
        require(params["state"] == state) { "Invalid authorization state" }
        if (params["error"] == "access_denied") throw ConsentDenied()
        require(params["error"] == null) { "Authorization failed" }
        val issued = params["client_id"] ?: clientId
        require(!issued.isNullOrBlank() && (clientId == null || issued == clientId)) { "Invalid client registration" }
        val code = params["code"]; require(!code.isNullOrBlank()) { "Missing authorization code" }
        return OAuthCallback(code, issued)
    }
}
