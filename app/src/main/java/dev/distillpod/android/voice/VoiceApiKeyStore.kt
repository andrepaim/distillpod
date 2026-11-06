package dev.distillpod.android.voice

import android.content.Context
import dev.distillpod.android.TokenVault

/** Device-only credential, isolated from ChatGPT credentials and portable preferences. */
class VoiceApiKeyStore(private val read: () -> String?, private val write: (String?) -> Unit) {
    private val changed = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val revisions: kotlinx.coroutines.flow.StateFlow<Long> = changed
    private constructor(vault: TokenVault) : this(vault::read, vault::write)
    constructor(context: Context) : this(TokenVault(context, "voice-api-key-vault", "distillpod-voice-api-key"))

    fun hasKey(): Boolean = runCatching { !read().isNullOrBlank() }.getOrDefault(false)
    fun save(value: String) {
        val key = value.trim()
        require(key.length in 20..512 && key.matches(Regex("sk-[A-Za-z0-9_-]+"))) {
            "Enter a valid OpenAI API key"
        }
        write(key)
        changed.value++
    }
    fun requireKey(): String = try {
        read()?.takeIf { it.isNotBlank() } ?: error("Add an OpenAI API key on the phone first")
    } catch (_: Exception) { error("OpenAI API key unavailable. Save it again on the phone.") }
    fun clear() { write(null); changed.value++ }
}
