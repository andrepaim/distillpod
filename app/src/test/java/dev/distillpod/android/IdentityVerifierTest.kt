package dev.distillpod.android
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class IdentityVerifierTest {
    private val key = RSAKeyGenerator(2048).keyID("test").generate()
    private fun token(nonce: String = "nonce", audience: String = "issued", expiry: Long = System.currentTimeMillis() + 60000): String {
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID("test").build(), JWTClaimsSet.Builder()
            .issuer("https://auth.openai.com").subject("account").audience(audience).expirationTime(Date(expiry)).claim("nonce", nonce).build())
        jwt.sign(RSASSASigner(key)); return jwt.serialize()
    }
    @Test fun `signed identity is bound to client and nonce`() {
        assertEquals("account", verifyIdentity(token(), JWKSet(key.toPublicJWK()), "issued", "nonce").subject)
    }
    @Test fun `rejects wrong nonce audience expired token and other signing key`() {
        assertThrows(IllegalArgumentException::class.java) { verifyIdentity(token("wrong"), JWKSet(key.toPublicJWK()), "issued", "nonce") }
        assertThrows(IllegalArgumentException::class.java) { verifyIdentity(token(audience = "other"), JWKSet(key.toPublicJWK()), "issued", "nonce") }
        assertThrows(IllegalArgumentException::class.java) { verifyIdentity(token(expiry = 0), JWKSet(key.toPublicJWK()), "issued", "nonce") }
        val other = RSAKeyGenerator(2048).keyID("test").generate()
        assertThrows(IllegalArgumentException::class.java) { verifyIdentity(token(), JWKSet(other.toPublicJWK()), "issued", "nonce") }
    }
}
