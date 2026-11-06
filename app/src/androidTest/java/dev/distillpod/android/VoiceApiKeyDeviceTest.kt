package dev.distillpod.android

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import dev.distillpod.android.voice.VoiceApiKeyStore
import org.junit.Assert.*
import org.junit.Test

/** Isolated fixture vault: never reads or replaces an owner's saved key. */
class VoiceApiKeyDeviceTest {
    @Test fun credentialIsEncryptedAndCanBeReopenedAndRemoved() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val namespace = "instrumentation-voice-key"
        val vault = TokenVault(context, namespace, "distillpod-test-voice-key")
        val store = VoiceApiKeyStore(vault::read, vault::write)
        val fixture = "sk-test-only-01234567890123456789"
        try {
            store.save(fixture)
            val persisted = context.getSharedPreferences(namespace, Context.MODE_PRIVATE).getString("session", null)
            assertNotNull(persisted); assertFalse(persisted!!.contains(fixture))
            val reopened = TokenVault(context, namespace, "distillpod-test-voice-key")
            assertEquals(fixture, VoiceApiKeyStore(reopened::read, reopened::write).requireKey())
            store.clear(); assertFalse(store.hasKey())
            assertFalse(context.getSharedPreferences(namespace, Context.MODE_PRIVATE).contains("session"))
        } finally { vault.write(null) }
    }
}
