package dev.distillpod.android.ai

import dev.distillpod.android.AuthSession
import dev.distillpod.android.SubscriptionProvider
import dev.distillpod.android.data.AppSettings
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call

data class AiText(val text: String, val model: String)
interface AiGateway { suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit = {}): AiText }

/**
 * The saved model while the account still offers it, otherwise the first available one, so a retired model, a plan change
 * or a preference synced from another device never blocks AI. The portable preference itself is left unchanged.
 */
fun resolveAiModel(saved: String, catalog: List<String>): String? = saved.takeIf { it.isNotBlank() && it in catalog } ?: catalog.firstOrNull()

class ChatGptGateway internal constructor(
    private val auth: AuthSession, private val selectedModel: suspend () -> String,
    private val provider: SubscriptionProvider = SubscriptionProvider(), private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : AiGateway {
    constructor(auth: AuthSession, settings: AppSettings) : this(auth, { settings.flow.first().selectedAiModel })
    companion object {
        private val executor = Executors.newCachedThreadPool { Thread(it, "distillpod-ai").apply { isDaemon = true } }
        /** Long enough to avoid a catalog request before every completion, short enough to follow plan changes. */
        const val CATALOG_TTL_MS = 5 * 60_000L
    }
    private class Catalog(val token: String, val models: List<String>, val fetchedAt: Long)
    private val catalog = AtomicReference<Catalog?>()
    /** Keyed by access token, so another account or a renewed token fetches its own catalog. */
    private fun models(token: String, register: (Call) -> Unit): List<String> {
        catalog.get()?.takeIf { it.token == token && clock() - it.fetchedAt in 0 until CATALOG_TTL_MS }?.let { return it.models }
        return provider.models(token, register).also { catalog.set(Catalog(token, it, clock())) }
    }
    override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit): AiText {
        val selected = selectedModel()
        return suspendCancellableCoroutine { continuation ->
            val active = AtomicReference<Call?>()
            executor.submit {
                try {
                    if (!continuation.isActive) return@submit
                    val register: (Call) -> Unit = { active.set(it); if (!continuation.isActive) it.cancel() }
                    val models = auth.withAccessToken { models(it, register) }
                    val model = resolveAiModel(selected, models) ?: error("No entitled text model")
                    if (!continuation.isActive) return@submit
                    val result = AiText(auth.withAccessToken { provider.respond(it, model, instructions, input, register, onDelta) }, model)
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    // A rejected request may mean entitlements changed: the next request reads a fresh catalog.
                    catalog.set(null)
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
            continuation.invokeOnCancellation { active.get()?.cancel() }
        }
    }
}
