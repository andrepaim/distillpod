package dev.distillpod.android.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

interface VoiceLease {
    val episode: String
    val position: Long
    suspend fun finish(resume: Boolean)
}
interface VoiceInput : AutoCloseable {
    fun start()
    fun read(): ByteArray?
}
interface VoiceOutput : AutoCloseable {
    suspend fun play(pcm: ByteArray)
    suspend fun drain()
}
interface VoiceConnection : AutoCloseable {
    suspend fun answer(input: VoiceInput, output: VoiceOutput, phase: (String) -> Unit, text: (String) -> Unit)
}
data class VoiceState(val phase: String = "Idle", val answer: String = "", val error: String? = null) {
    val active get() = phase != "Idle"
}
/** One job owns all resources; cancellation joins cleanup before another interaction starts. */
class VoiceCoordinator(
    private val scope: CoroutineScope,
    private val pause: suspend (onChanged: () -> Unit) -> VoiceLease,
    private val context: suspend (VoiceLease) -> String,
    private val connect: suspend (String) -> VoiceConnection,
    private val output: () -> VoiceOutput,
) {
    private val mutable = MutableStateFlow(VoiceState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var resume = false
    fun start(input: () -> VoiceInput, diagnostic: Boolean = false) {
        if (job != null) return
        resume = true
        mutable.value = VoiceState("Preparing")
        job = scope.launch(start = CoroutineStart.LAZY) {
            var lease: VoiceLease? = null
            try {
                withTimeout(90_000) {
                    lease = pause { cancel(false) }
                    val source = if (diagnostic) "" else context(lease!!)
                    output().use { speaker ->
                        if (diagnostic) {
                            input().use { mic ->
                                voiceReadyTone(); mic.start(); mutable.value = VoiceState("Listening")
                                val capture = async(Dispatchers.IO) {
                                    val bytes = java.io.ByteArrayOutputStream()
                                    while (bytes.size() < 144000) { ensureActive(); val chunk = mic.read() ?: error("Microphone dismissed"); bytes.write(chunk) }
                                    bytes.toByteArray()
                                }
                                val audio = try { withTimeout(5_000) { capture.await() } }
                                    finally { mic.close(); capture.cancelAndJoin() }
                                mutable.value = VoiceState("Answering")
                                speaker.play(audio); speaker.drain()
                            }
                        } else connect(source).use { connection ->
                            input().use { mic -> connection.answer(mic, speaker,
                                { mutable.value = mutable.value.copy(phase = it) },
                                { mutable.value = mutable.value.copy(answer = (mutable.value.answer + it).take(8000)) }) }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                if (cancelled is TimeoutCancellationException) mutable.value = mutable.value.copy(error = "Voice request timed out")
                else throw cancelled
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(error = "Voice unavailable. Check transcript, microphone permission, Google sign-in and voice access.")
            } finally {
                try { withContext(NonCancellable) { lease?.finish(resume) } }
                finally { mutable.value = mutable.value.copy(phase = "Idle"); job = null }
            }
        }
        job?.start()
    }
    fun cancel(shouldResume: Boolean) { resume = shouldResume; job?.cancel() }
}
