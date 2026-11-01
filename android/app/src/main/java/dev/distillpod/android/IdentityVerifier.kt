package dev.distillpod.android
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT

data class VerifiedIdentity(val subject: String, val email: String?)
fun verifyIdentity(encoded: String, keys: JWKSet, clientId: String, nonce: String): VerifiedIdentity {
    try {
        val jwt = SignedJWT.parse(encoded)
        require(jwt.header.algorithm == JWSAlgorithm.RS256) { "Unsupported identity signature" }
        val key = keys.getKeyByKeyId(jwt.header.keyID) as? RSAKey ?: error("Unknown identity signing key")
        require(jwt.verify(RSASSAVerifier(key))) { "Invalid identity signature" }
        val claims = jwt.jwtClaimsSet
        require(claims.issuer == "https://auth.openai.com")
        require(clientId in claims.audience)
        require(claims.expirationTime != null && claims.expirationTime.time > System.currentTimeMillis())
        require(claims.getStringClaim("nonce") == nonce)
        require(!claims.subject.isNullOrBlank())
        return VerifiedIdentity(claims.subject, claims.getStringClaim("email"))
    } catch (e: IllegalArgumentException) { throw e }
    catch (e: Exception) { throw IllegalArgumentException("Invalid identity token", e) }
}
