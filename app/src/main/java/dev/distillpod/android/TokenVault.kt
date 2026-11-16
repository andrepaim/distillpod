package dev.distillpod.android
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.distillpod.core.openToken
import dev.distillpod.core.sealToken
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Encrypted single-value secret storage. [read] may throw when the stored value can no longer be opened. */
interface CredentialStore {
    fun read(): String?
    fun write(value: String?)
}

class TokenVault(context: Context, namespace: String = "chatgpt-vault", private val keyAlias: String = "distillpod-credentials") : CredentialStore {
    private val preferences = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(keyAlias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    @Synchronized override fun read(): String? = preferences.getString("session", null)?.let { openToken(Base64.decode(it, Base64.NO_WRAP), key()) }
    @Synchronized override fun write(value: String?) {
        val edit = preferences.edit()
        if(value == null) edit.remove("session") else edit.putString("session", Base64.encodeToString(sealReplacing(value, ::key, ::storedValueReadable, ::deleteKey), Base64.NO_WRAP))
        check(edit.commit()) { "Unable to save account connection" }
    }
    private fun storedValueReadable() = runCatching { read() != null }.getOrDefault(false)
    private fun deleteKey() { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias) }
}

/**
 * A write replaces the stored value. If the Keystore key can no longer encrypt (for example after Android invalidated it),
 * replace the key so the user can reconnect — but only when the value it protects is already unreadable or absent, so a
 * readable credential is never discarded.
 */
internal fun sealReplacing(value: String, key: () -> SecretKey, existingReadable: () -> Boolean, deleteKey: () -> Unit): ByteArray =
    try { sealToken(value, key()) } catch (error: Exception) {
        if (error !is GeneralSecurityException && error !is ProviderException || existingReadable()) throw error
        deleteKey()
        sealToken(value, key())
    }
