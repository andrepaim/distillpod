package dev.distillpod.android.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
    fun finishQuestion() {}
    suspend fun answer(input: VoiceInput, output: VoiceOutput, phase: (String) -> Unit, text: (String) -> Unit)
}
enum class VoiceOrigin { PHONE, CAR, HEADPHONES }
internal class VoiceSetupFailure(message: String) : Exception(message)
internal class HeadsetDisconnected : Exception()
data class VoiceState(val phase: String = "Idle", val answer: String = "", val error: String? = null, val level: Float = 0f,
    val origin: VoiceOrigin? = null) {
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
    private var connection: VoiceConnection? = null
    fun start(input: () -> VoiceInput, diagnostic: Boolean = false, origin: VoiceOrigin = VoiceOrigin.PHONE,
        prepareAudio: suspend () -> AutoCloseable = { AutoCloseable {} }) {
        if (job != null) return
        resume = true
        mutable.value = VoiceState("Preparing", origin = origin)
        job = scope.launch(start = CoroutineStart.LAZY) {
            var lease: VoiceLease? = null
            try {
                withTimeout(90_000) {
                    lease = pause { cancel(false) }
                    val source = if (diagnostic) "" else context(lease!!)
                    prepareAudio().use {
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
                            this@VoiceCoordinator.connection = connection
                            input().use { mic -> connection.answer(object : VoiceInput {
                                override fun start() = mic.start()
                                override fun read(): ByteArray? = mic.read()?.also { bytes ->
                                    mutable.update { if (it.phase == "Listening") it.copy(level = microphoneLevel(bytes)) else it }
                                }
                                override fun close() = mic.close()
                            }, speaker,
                                { mutable.value = mutable.value.copy(phase = it, level = 0f) },
                                { mutable.value = mutable.value.copy(answer = (mutable.value.answer + it).take(8000)) }) }
                        }
                    }
                    }
                }
            } catch (cancelled: CancellationException) {
                if (cancelled is TimeoutCancellationException) mutable.value = mutable.value.copy(error = "Voice request timed out")
                else throw cancelled
            } catch (error: Exception) {
                if (error is HeadsetDisconnected) resume = false
                mutable.value = mutable.value.copy(error = if (error is HeadsetDisconnected)
                    "Headset disconnected. Reconnect it before asking again."
                    else if (error is VoiceSetupFailure) error.message
                    else if (mutable.value.phase == "Answering")
                    "The voice answer was interrupted. You can ask again."
                    else "Voice unavailable. Check transcript, microphone permission, your saved OpenAI API key.")
            } finally {
                connection = null
                mutable.value = mutable.value.copy(phase = "Finishing", level = 0f)
                try { withContext(NonCancellable) { lease?.finish(resume) } }
                finally { mutable.value = mutable.value.copy(phase = "Idle", origin = null); job = null }
            }
        }
        job?.start()
    }
    fun finishQuestion() { if (mutable.value.phase == "Listening") connection?.finishQuestion() }
    fun cancel(shouldResume: Boolean) { resume = shouldResume; job?.cancel() }
    fun cancelFrom(origin: VoiceOrigin, shouldResume: Boolean) {
        if (mutable.value.origin == origin) cancel(shouldResume)
    }
}
