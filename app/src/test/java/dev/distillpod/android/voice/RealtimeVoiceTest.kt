package dev.distillpod.android.voice

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class RealtimeVoiceTest {
    @Test fun `websocket sends audio once configured and drains final answer`() = runBlocking { exchange() }

    @Test fun `fast provider bursts survive slower audio playback`() = runBlocking { exchange(chunks = 200, slow = true) }

    @Test fun `Done explicitly commits a question without waiting for VAD`() = runBlocking { exchange(manual = true) }

    @Test fun `Done does not commit twice when VAD already finished`() = runBlocking { exchange(manual = true, committedDuringDisable = true) }

    @Test fun `complete long source arrives outside instructions before the microphone starts`() = runBlocking {
        exchange(source = "Opening context. " + "explicação 🧠 \"quoted\"\n".repeat(15000) + " Current moment.", verifySource = true)
    }
    @Test fun `source frames preserve surrogate pairs escaping and controls at the boundary`() = runBlocking {
        exchange(source = "a".repeat(15_999) + "🧠\u0001\t\n\\\"" + "z".repeat(20_000), verifySource = true)
    }
    @Test fun `unrelated acknowledgements and provisional added events cannot advance source upload`() = runBlocking {
        exchange(source = "bounded ".repeat(10_000), verifySource = true, delayedAcks = true)
    }
    @Test fun `legacy created acknowledgements still accept the full source`() = runBlocking {
        exchange(source = "Earlier context ".repeat(2000), verifySource = true, legacyAcks = true)
    }
    @Test fun `provider context limits during preparation are explained before recording`() = runBlocking { contextLimit(false) }
    @Test fun `provider context limits at response creation are explained without an answer`() = runBlocking { contextLimit(true) }

    @Test fun `cancelling a stalled source upload never starts recording or playback`() = runBlocking {
        val server = MockWebServer()
        val sourceStarted = CompletableDeferred<Unit>()
        var ready = false; var recording = false
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(socket: WebSocket, response: Response) { socket.send("""{"type":"session.created"}""") }
            override fun onMessage(socket: WebSocket, text: String) {
                when (JSONObject(text).getString("type")) {
                    "session.update" -> socket.send("""{"type":"session.updated"}""")
                    "conversation.item.create" -> sourceStarted.complete(Unit) // Deliberately never acknowledge.
                }
            }
        }))
        try {
            val input = object : VoiceInput {
                override fun start() { recording = true }
                override fun read(): ByteArray? = error("Recording cannot begin with incomplete context")
                override fun close() {}
            }
            val output = object : VoiceOutput {
                override suspend fun play(pcm: ByteArray) { fail("Incomplete context cannot produce an answer") }
                override suspend fun drain() { fail("Incomplete context cannot finish an answer") }
                override fun close() {}
            }
            RealtimeVoice("Synthetic source", "fixture-key", ready = { ready = true }, endpoint = server.url("/realtime").toString()).use {
                val job = launch { it.answer(input, output, {}, {}) }
                withTimeout(5000) { sourceStarted.await() }
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
            }
            assertFalse(ready); assertFalse(recording)
        } finally { server.shutdown() }
    }

    private suspend fun contextLimit(atResponse: Boolean) {
        val server = MockWebServer()
        var recordingStarted = false; var recordingClosed = false
        val failure = """{"code":"context_length_exceeded","message":"private-provider-sentinel"}"""
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(socket: WebSocket, response: Response) { socket.send("""{"type":"session.created"}""") }
            override fun onMessage(socket: WebSocket, text: String) {
                val e = JSONObject(text)
                when (e.getString("type")) {
                    "session.update" -> socket.send("""{"type":"session.updated"}""")
                    "conversation.item.create" -> if (atResponse)
                        socket.send(JSONObject().put("type", "conversation.item.done").put("item", e.getJSONObject("item")).toString())
                    else socket.send("""{"type":"error","error":$failure}""")
                    "input_audio_buffer.append" -> {
                        socket.send("""{"type":"input_audio_buffer.speech_started"}""")
                        socket.send("""{"type":"input_audio_buffer.speech_stopped"}""")
                    }
                    "response.create" -> socket.send("""{"type":"response.done","response":{"status":"failed","status_details":{"error":$failure}}}""")
                }
            }
        }))
        try {
            val input = object : VoiceInput {
                override fun start() { recordingStarted = true }
                override fun read(): ByteArray { Thread.sleep(10); return byteArrayOf(0,0) }
                override fun close() { recordingClosed = true }
            }
            val output = object : VoiceOutput {
                override suspend fun play(pcm: ByteArray) { fail("A rejected context cannot produce audio") }
                override suspend fun drain() { fail("A rejected context cannot finish an answer") }
                override fun close() {}
            }
            val error = runCatching {
                RealtimeVoice("Synthetic source", "fixture-key", ready = {}, endpoint = server.url("/realtime").toString()).use {
                    withTimeout(5000) { it.answer(input, output, {}, {}) }
                }
            }.exceptionOrNull()
            assertTrue(error is VoiceSetupFailure)
            assertTrue(error!!.message!!.contains("context limit"))
            assertFalse(error.message!!.contains("private-provider-sentinel"))
            assertEquals(atResponse, recordingStarted)
            assertEquals(atResponse, recordingClosed)
        } finally { server.shutdown() }
    }

    private suspend fun exchange(chunks: Int = 1, slow: Boolean = false, manual: Boolean = false, committedDuringDisable: Boolean = false,
        source: String = "source", verifySource: Boolean = false, delayedAcks: Boolean = false, legacyAcks: Boolean = false) {
        val server = MockWebServer()
        var configured = false; var responseRequested = false; var explicitCommits = 0
        val receivedSource = StringBuilder()
        val sourceMessages = mutableListOf<String>()
        var configuredSession: JSONObject? = null
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val pendingAcks = AtomicInteger(0)
        val prematureSource = AtomicBoolean(false)
        lateinit var connection: RealtimeVoice
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(socket: WebSocket, response: Response) { socket.send("""{"type":"session.created"}""") }
            override fun onMessage(socket: WebSocket, text: String) {
                val event = JSONObject(text)
                when (event.getString("type")) {
                    "session.update" -> {
                        if (!configured) configuredSession = event.getJSONObject("session")
                        val input = event.getJSONObject("session").getJSONObject("audio").getJSONObject("input")
                        if (input.isNull("turn_detection")) {
                            if (committedDuringDisable) socket.send("""{"type":"input_audio_buffer.committed"}""")
                        } else assertEquals(false, input.getJSONObject("turn_detection").getBoolean("create_response"))
                        configured = true; socket.send("""{"type":"session.updated"}""")
                    }
                    "conversation.item.create" -> {
                        if (pendingAcks.incrementAndGet() != 1) prematureSource.set(true)
                        val item = event.getJSONObject("item")
                        assertEquals("user", item.getString("role"))
                        val content = item.getJSONArray("content").getJSONObject(0)
                        assertEquals("input_text", content.getString("type"))
                        receivedSource.append(content.getString("text").substringAfter("\n"))
                        sourceMessages += text
                        if (delayedAcks) {
                            socket.send("""{"type":"conversation.item.done","item":{"id":"unrelated"}}""")
                            socket.send(JSONObject().put("type", "conversation.item.added").put("item", item).toString())
                        }
                        scheduler.schedule({
                            pendingAcks.decrementAndGet()
                            socket.send(JSONObject().put("type", if (legacyAcks) "conversation.item.created" else "conversation.item.done")
                                .put("item", item).toString())
                        }, if (delayedAcks) 30 else 0, TimeUnit.MILLISECONDS)
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
            override fun start() { if (verifySource) {
                assertTrue("Complete source must arrive before recording", source == receivedSource.toString())
                assertEquals(0, pendingAcks.get())
            } }
            override fun read(): ByteArray { Thread.sleep(20); if (manual) connection.finishQuestion(); return byteArrayOf(0,0) }
            override fun close() { microphoneClosed = true }
        }
        val output = object : VoiceOutput {
            override suspend fun play(pcm: ByteArray) { if (slow) delay(5); played++; assertTrue(microphoneClosed); assertArrayEquals(byteArrayOf(0,0), pcm) }
            override suspend fun drain() { drained = true }
            override fun close() {}
        }
        try {
            RealtimeVoice(source, "sk-test-only-01234567890123456789", ready = {}, endpoint = server.url("/realtime").toString()).use { session ->
                connection = session
                withTimeout(5000) { connection.answer(input, output, {}, { answer += it }) }
            }
            assertTrue(drained); assertEquals(chunks, played); assertEquals("Answer", answer)
            if (manual) assertEquals(if (committedDuringDisable) 0 else 1, explicitCommits)
            if (verifySource) {
                assertEquals("disabled", configuredSession!!.getString("truncation"))
                assertFalse(configuredSession!!.getString("instructions").contains("Opening context."))
                assertTrue(sourceMessages.size > 1)
                assertTrue(sourceMessages.all { it.toByteArray(Charsets.UTF_8).size < 256000 })
                assertFalse(prematureSource.get())
            }
            assertEquals("Bearer sk-test-only-01234567890123456789", server.takeRequest().getHeader("Authorization"))
        } finally { scheduler.shutdownNow(); server.shutdown() }
    }
}
