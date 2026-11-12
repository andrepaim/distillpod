package dev.distillpod.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic vault and intercepted responses; never reads the owner's credentials or uses a network. */
class AuthVaultDeviceTest {
    @Test fun refreshFailuresPreserveNewCredentialsAndClearOnlyMatchingInvalidGrant() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = TokenVault(context, "auth143-fixture", "distillpod-test-auth143")
        fun envelope(refresh: String) = JSONObject().put("access_token", "synthetic-access")
            .put("refresh_token", refresh).put("client_id", "fixture").put("subject", "fixture")
            .put("expires_at", 0).toString()
        var reply = 403
        var rotateDuringRequest = false
        var connected = true
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (rotateDuringRequest) vault.write(envelope("newer"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(reply).message("fixture")
                .body("""{"error":"invalid_grant"}""".toResponseBody()).build()
        }.build()
        val session = AuthSession(vault::read, vault::write, "https://fixture.invalid/".toHttpUrl(), client,
            onCredentialsChanged = { connected = it })
        try {
            vault.write(envelope("old"))
            // A 403 without an invalid_grant must preserve the account.
            val forbidden = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("fixture")
                    .body("""{"error":"access_denied"}""".toResponseBody()).build()
            }.build()
            val denied = AuthSession(vault::read, vault::write, "https://fixture.invalid/".toHttpUrl(), forbidden)
            assertThrows(IllegalStateException::class.java) { denied.accessToken() }
            assertEquals("old", JSONObject(vault.read()!!).getString("refresh_token"))
            reply = 400; rotateDuringRequest = true
            assertThrows(IllegalStateException::class.java) { session.accessToken() }
            assertEquals("newer", JSONObject(TokenVault(context, "auth143-fixture", "distillpod-test-auth143").read()!!).getString("refresh_token"))
            assertTrue(connected)
            rotateDuringRequest = false
            assertThrows(ReauthorizationRequired::class.java) { session.accessToken() }
            assertNull(vault.read()); assertFalse(connected)
        } finally { vault.write(null) }
    }
}
