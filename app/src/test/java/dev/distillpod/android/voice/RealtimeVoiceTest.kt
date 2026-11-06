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
    @Test fun `websocket sends audio once configured and drains final answer`() = runBlocking {
        val server = MockWebServer()
        var configured = false; var responseRequested = false
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(socket: WebSocket, response: Response) { socket.send("""{"type":"session.created"}""") }
            override fun onMessage(socket: WebSocket, text: String) {
                val event = JSONObject(text)
                when (event.getString("type")) {
                    "session.update" -> {
                        assertEquals(false, event.getJSONObject("session").getJSONObject("audio").getJSONObject("input").getJSONObject("turn_detection").getBoolean("create_response"))
                        configured = true; socket.send("""{"type":"session.updated"}""")
                    }
                    "input_audio_buffer.append" -> if (!responseRequested) {
                        assertTrue(configured)
                        socket.send("""{"type":"input_audio_buffer.speech_started"}""")
                        socket.send("""{"type":"input_audio_buffer.speech_stopped"}""")
                    }
                    "response.create" -> {
                        responseRequested = true
                        socket.send("""{"type":"response.output_audio.delta","delta":"AAA="}""")
                        socket.send("""{"type":"response.output_audio_transcript.delta","delta":"Answer"}""")
                        socket.send("""{"type":"response.done","response":{"status":"completed","output":[]}}""")
                    }
                }
            }
        }))
        var microphoneClosed = false; var drained = false; var answer = ""
        val input = object : VoiceInput {
            override fun start() {}
            override fun read(): ByteArray { Thread.sleep(20); return byteArrayOf(0,0) }
            override fun close() { microphoneClosed = true }
        }
        val output = object : VoiceOutput {
            override suspend fun play(pcm: ByteArray) { assertTrue(microphoneClosed); assertArrayEquals(byteArrayOf(0,0), pcm) }
            override suspend fun drain() { drained = true }
            override fun close() {}
        }
        try {
            RealtimeVoice("source", "sk-test-only-01234567890123456789", ready = {}, endpoint = server.url("/realtime").toString()).use { connection ->
                withTimeout(5000) { connection.answer(input, output, {}, { answer += it }) }
            }
            assertTrue(drained); assertEquals("Answer", answer)
            assertEquals("Bearer sk-test-only-01234567890123456789", server.takeRequest().getHeader("Authorization"))
        } finally { server.shutdown() }
    }
}
