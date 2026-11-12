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

class ChatGptGateway(private val auth: AuthSession, private val settings: AppSettings) : AiGateway {
    companion object { private val executor = Executors.newCachedThreadPool { Thread(it, "distillpod-ai").apply { isDaemon = true } } }
    override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit): AiText {
        val selected = settings.flow.first().selectedAiModel
        return suspendCancellableCoroutine { continuation ->
            val active = AtomicReference<Call?>()
            executor.submit {
                try {
                    if (!continuation.isActive) return@submit
                    val provider = SubscriptionProvider()
                    val register: (Call) -> Unit = { active.set(it); if (!continuation.isActive) it.cancel() }
                    val models = auth.withAccessToken { provider.models(it, register) }
                    val model = if (selected.isBlank()) models.firstOrNull() ?: error("No entitled text model") else selected.also { require(it in models) { "Choose an available AI model in Settings" } }
                    if (!continuation.isActive) return@submit
                    val result = AiText(auth.withAccessToken { provider.respond(it, model, instructions, input, register, onDelta) }, model)
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }
            continuation.invokeOnCancellation { active.get()?.cancel() }
        }
    }
}
