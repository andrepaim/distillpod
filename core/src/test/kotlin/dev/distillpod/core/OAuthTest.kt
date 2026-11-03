package dev.distillpod.core
import org.junit.Assert.*
import org.junit.Test

class OAuthTest {
    @Test fun `PKCE matches RFC7636 vector`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }
    @Test fun `callback validates state and returned registration`() {
        val pending = OAuthAttempt("state", "nonce", "verifier", "http://127.0.0.1:1455/auth/callback")
        val result = pending.callback("http://127.0.0.1:1455/auth/callback?state=state&code=abc&client_id=issued")
        assertEquals("issued", result.clientId)
        assertEquals("abc", result.code)
        assertThrows(IllegalArgumentException::class.java) { pending.callback("http://127.0.0.1:1455/auth/callback?state=wrong&code=abc&client_id=issued") }
    }
    @Test fun `returning registration cannot switch clients`() {
        val pending = OAuthAttempt("s", "n", "v", "http://127.0.0.1:1455/auth/callback", "original")
        assertThrows(IllegalArgumentException::class.java) { pending.callback("http://127.0.0.1:1455/auth/callback?state=s&code=c&client_id=other") }
    }
    @Test fun `rejects callback host substitution and duplicate state`() {
        val p = OAuthAttempt("s", "n", "v", "http://127.0.0.1:1455/auth/callback")
        assertThrows(IllegalArgumentException::class.java) { p.callback("http://evil.test/auth/callback?state=s&code=c&client_id=i") }
        assertThrows(IllegalArgumentException::class.java) { p.callback("http://127.0.0.1:1455/auth/callback?state=s&state=s&code=c&client_id=i") }
    }
    @Test fun `consent denial does not exchange code`() {
        val p = OAuthAttempt("s", "n", "v", "http://127.0.0.1:1455/auth/callback")
        assertThrows(ConsentDenied::class.java) { p.callback("http://127.0.0.1:1455/auth/callback?state=s&error=access_denied") }
    }
}
