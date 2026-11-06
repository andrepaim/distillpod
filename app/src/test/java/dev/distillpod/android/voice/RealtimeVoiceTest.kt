package dev.distillpod.android.voice

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RealtimeVoiceTest {
    @Test fun `websocket sends audio once configured and drains final answer`() = runBlocking { exchange() }

    @Test fun `fast provider bursts survive slower audio playback`() = runBlocking { exchange(chunks = 200, slow = true) }

    @Test fun `Done explicitly commits a question without waiting for VAD`() = runBlocking { exchange(manual = true) }

    @Test fun `Done does not commit twice when VAD already finished`() = runBlocking { exchange(manual = true, committedDuringDisable = true) }

    private suspend fun exchange(chunks: Int = 1, slow: Boolean = false, manual: Boolean = false, committedDuringDisable: Boolean = false) {
        val server = MockWebServer()
        var configured = false; var responseRequested = false; var explicitCommits = 0
        lateinit var connection: RealtimeVoice
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(socket: WebSocket, response: Response) { socket.send("""{"type":"session.created"}""") }
            override fun onMessage(socket: WebSocket, text: String) {
                val event = JSONObject(text)
                when (event.getString("type")) {
                    "session.update" -> {
                        val input = event.getJSONObject("session").getJSONObject("audio").getJSONObject("input")
                        if (input.isNull("turn_detection")) {
                            if (committedDuringDisable) socket.send("""{"type":"input_audio_buffer.committed"}""")
                        } else assertEquals(false, input.getJSONObject("turn_detection").getBoolean("create_response"))
                        configured = true; socket.send("""{"type":"session.updated"}""")
                    }
                    "input_audio_buffer.append" -> if (!responseRequested) {
                        assertTrue(configured)
                        socket.send("""{"type":"input_audio_buffer.speech_started"}""")
                        if (!manual) socket.send("""{"type":"input_audio_buffer.speech_stopped"}""")
                    }
                    "input_audio_buffer.commit" -> {
                        explicitCommits++
                        socket.send("""{"type":"input_audio_buffer.committed"}""")
                    }
                    "response.create" -> {
                        responseRequested = true
                        repeat(chunks) { socket.send("""{"type":"response.output_audio.delta","delta":"AAA="}""") }
                        socket.send("""{"type":"response.output_audio_transcript.delta","delta":"Answer"}""")
                        socket.send("""{"type":"response.done","response":{"status":"completed","output":[]}}""")
                    }
                }
            }
        }))
        var microphoneClosed = false; var drained = false; var answer = ""; var played = 0
        val input = object : VoiceInput {
            override fun start() {}
            override fun read(): ByteArray { Thread.sleep(20); if (manual) connection.finishQuestion(); return byteArrayOf(0,0) }
            override fun close() { microphoneClosed = true }
        }
        val output = object : VoiceOutput {
            override suspend fun play(pcm: ByteArray) { if (slow) delay(5); played++; assertTrue(microphoneClosed); assertArrayEquals(byteArrayOf(0,0), pcm) }
            override suspend fun drain() { drained = true }
            override fun close() {}
        }
        try {
            RealtimeVoice("source", "sk-test-only-01234567890123456789", ready = {}, endpoint = server.url("/realtime").toString()).use { session ->
                connection = session
                withTimeout(5000) { connection.answer(input, output, {}, { answer += it }) }
            }
            assertTrue(drained); assertEquals(chunks, played); assertEquals("Answer", answer)
            if (manual) assertEquals(if (committedDuringDisable) 0 else 1, explicitCommits)
            assertEquals("Bearer sk-test-only-01234567890123456789", server.takeRequest().getHeader("Authorization"))
        } finally { server.shutdown() }
    }
}
