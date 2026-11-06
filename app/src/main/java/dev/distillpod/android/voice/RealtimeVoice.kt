package dev.distillpod.android.voice

import android.util.Base64
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class VoiceUsage(val elapsedMs: Long, val firstAudioMs: Long?, val inputTokens: Int, val outputTokens: Int)

class RealtimeVoice(private val source: String, apiKey: String, private val search: suspend (String) -> String = { "[]" },
    private val http: OkHttpClient = OkHttpClient.Builder().pingInterval(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build(),
    private val ready: suspend () -> Unit = { voiceReadyTone() },
    endpoint: String = "wss://api.openai.com/v1/realtime?model=gpt-realtime-2.1-mini",
) : VoiceConnection {
    companion object {
        private val lastUsage = kotlinx.coroutines.flow.MutableStateFlow<VoiceUsage?>(null)
        val usage: kotlinx.coroutines.flow.StateFlow<VoiceUsage?> = lastUsage
    }
    private val events = RealtimeEventBuffer()
    private val socket = http.newWebSocket(Request.Builder().url(endpoint)
        .header("Authorization", "Bearer $apiKey").build(), object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!events.offer(text)) webSocket.cancel()
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { events.close(IllegalStateException("Voice connection failed")) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { events.close() }
    })
    private fun send(value: JSONObject) { check(socket.queueSize() < 256_000 && socket.send(value.toString())) { "Voice connection congested" } }
    private suspend fun event() = JSONObject(events.receive()).also { check(it.optString("type") != "error") { "Voice request rejected" } }
    override suspend fun answer(input: VoiceInput, output: VoiceOutput, phase: (String) -> Unit, text: (String) -> Unit) = coroutineScope {
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        var firstAudioMs: Long? = null
        var inputTokens = 0
        var outputTokens = 0
        try {
        withTimeout(10_000) { while (event().optString("type") != "session.created") {} }
        val audioInput = JSONObject().put("format", JSONObject().put("type", "audio/pcm").put("rate", 24000))
            .put("turn_detection", JSONObject().put("type", "server_vad").put("create_response", false)
                .put("interrupt_response", false).put("silence_duration_ms", 900))
        val session = JSONObject().put("type", "realtime")
            .put("instructions", VoiceContext.INSTRUCTIONS + "\nSource data:\n" + source)
            .put("output_modalities", org.json.JSONArray().put("audio")).put("max_output_tokens", 1200)
            .put("audio", JSONObject().put("input", audioInput))
        val tool = JSONObject().put("type", "function").put("name", "search_episode")
            .put("description", "Find earlier transcript passages before the current listening position")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("query", JSONObject().put("type", "string")))
                .put("required", org.json.JSONArray().put("query")).put("additionalProperties", false))
        session.put("tools", org.json.JSONArray().put(tool))
        send(JSONObject().put("type", "session.update").put("session", session))
        withTimeout(10_000) { while (event().optString("type") != "session.updated") {} }
        ready(); phase("Listening"); input.start()
        val reader = launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val bytes = input.read() ?: error("Microphone dismissed")
                    ensureActive()
                    send(JSONObject().put("type", "input_audio_buffer.append").put("audio", Base64.encodeToString(bytes, Base64.NO_WRAP)))
                }
            } catch (e: Exception) { ensureActive(); throw e }
        }
        try {
            withTimeout(8_000) { while (event().optString("type") != "input_audio_buffer.speech_started") {} }
            withTimeout(30_000) { while (event().optString("type") != "input_audio_buffer.speech_stopped") {} }
        } finally { reader.cancel(); input.close(); reader.join() }
        phase("Answering")
        send(JSONObject().put("type", "response.create"))
        var firstAudio = true
        var searches = 0
        val answerStarted = kotlin.time.TimeSource.Monotonic.markNow()
        withTimeout(60_000) {
            while (true) {
                val e = if (firstAudio) withTimeout((15_000 - answerStarted.elapsedNow().inWholeMilliseconds).coerceAtLeast(1)) { event() } else event()
                when (e.optString("type")) {
                    "response.output_audio.delta" -> { if (firstAudioMs == null) firstAudioMs = started.elapsedNow().inWholeMilliseconds; firstAudio = false; output.play(Base64.decode(e.getString("delta"), Base64.DEFAULT)) }
                    "response.output_audio_transcript.delta" -> text(e.getString("delta"))
                    "response.done" -> {
                        val tokens = e.getJSONObject("response").optJSONObject("usage")
                        inputTokens += tokens?.optInt("input_tokens") ?: 0
                        outputTokens += tokens?.optInt("output_tokens") ?: 0
                        check(e.getJSONObject("response").optString("status") == "completed")
                        val items = e.getJSONObject("response").optJSONArray("output") ?: org.json.JSONArray()
                        var called = false
                        for (index in 0 until items.length()) {
                            val item = items.getJSONObject(index)
                            if (item.optString("type") != "function_call") continue
                            check(item.getString("name") == "search_episode" && searches++ < 2) { "Retrieval limit reached" }
                            val query = JSONObject(item.getString("arguments")).getString("query")
                            val result = search(query)
                            send(JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
                                .put("type", "function_call_output").put("call_id", item.getString("call_id")).put("output", result)))
                            called = true
                        }
                        if (called) { send(JSONObject().put("type", "response.create")); continue }
                        check(!firstAudio) { "No spoken answer" }; break
                    }
                }
            }
        }
        output.drain()
        } finally { lastUsage.value = VoiceUsage(started.elapsedNow().inWholeMilliseconds, firstAudioMs, inputTokens, outputTokens) }
    }
    override fun close() { socket.cancel(); events.cancel(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
}
