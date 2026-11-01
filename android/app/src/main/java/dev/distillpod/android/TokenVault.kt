package dev.distillpod.android
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.distillpod.core.openToken
import dev.distillpod.core.sealToken
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class TokenVault(context: Context) {
    private val preferences = context.getSharedPreferences("chatgpt-vault", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("distillpod-credentials", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("distillpod-credentials", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    @Synchronized fun read(): String? = preferences.getString("session", null)?.let { openToken(Base64.decode(it, Base64.NO_WRAP), key()) }
    @Synchronized fun write(value: String?) {
        val edit = preferences.edit()
        if(value == null) edit.remove("session") else edit.putString("session", Base64.encodeToString(sealToken(value, key()), Base64.NO_WRAP))
        check(edit.commit()) { "Unable to save account connection" }
    }
}
