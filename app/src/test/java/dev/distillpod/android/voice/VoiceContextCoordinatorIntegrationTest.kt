package dev.distillpod.android.voice

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import kotlinx.coroutines.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceContextCoordinatorIntegrationTest {
    private suspend fun idle(coordinator: VoiceCoordinator) = withTimeout(5000) {
        while (coordinator.state.value.active) delay(10)
    }
    @Test fun `old prefix fails before pausing then ready partial retry delivers complete source`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        val server = MockWebServer()
        var pauses = 0; var connections = 0; var micCreated = 0; var micStarted = false; var restored = 0
        val received = StringBuilder()
        var expected = ""
        var inputClosed = false; var answerChunks = 0
        try {
            val dao = db.library()
            dao.putPodcast(PodcastEntity("pod", "https://example.invalid/feed", "Synthetic show"))
            dao.putEpisode(EpisodeEntity("ep", "pod", "guid", "Synthetic episode", "https://example.invalid/audio", mediaFingerprint = "audio"))
            dao.putTranscript(TranscriptEntity("ep", "rev", "audio", "tiny", "en", state = "partial"))
            dao.putSegments(listOf(SegmentEntity("ep", "rev", 0, 1000, "Old valid speech")))
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(socket: WebSocket, response: Response) { socket.send("""{"type":"session.created"}""") }
                override fun onMessage(socket: WebSocket, text: String) {
                    val event = JSONObject(text)
                    when (event.getString("type")) {
                        "session.update" -> socket.send("""{"type":"session.updated"}""")
                        "conversation.item.create" -> {
                            val item = event.getJSONObject("item")
                            received.append(item.getJSONArray("content").getJSONObject(0).getString("text").substringAfter("\n"))
                            socket.send(JSONObject().put("type", "conversation.item.done").put("item", item).toString())
                        }
                        "input_audio_buffer.append" -> {
                            socket.send("""{"type":"input_audio_buffer.speech_started"}""")
                            socket.send("""{"type":"input_audio_buffer.speech_stopped"}""")
                        }
                        "response.create" -> {
                            socket.send("""{"type":"response.output_audio.delta","delta":"AAA="}""")
                            socket.send("""{"type":"response.done","response":{"status":"completed","output":[]}}""")
                        }
                    }
                }
            }))
            val coordinator = VoiceCoordinator(this, {
                pauses++
                object : VoiceLease {
                    override val episode = "ep"; override val position = 300000L
                    override suspend fun finish(resume: Boolean) { assertTrue(resume); restored++ }
                }
            }, { VoiceContext.load(db, it.episode, it.position) }, {
                connections++
                RealtimeVoice(it, "synthetic-fixture-key", ready = {}, endpoint = server.url("/realtime").toString())
            }, { object : VoiceOutput {
                override suspend fun play(pcm: ByteArray) {
                    assertTrue(inputClosed); assertArrayEquals(byteArrayOf(0, 0), pcm); answerChunks++
                }
                override suspend fun drain() { assertTrue(inputClosed) }
                override fun close() {}
            } }, diagnosticFailure = { throw AssertionError("Unexpected synthetic voice failure", it) },
                preflight = { VoiceContext.check(db, "ep", 300000) })
            val input = {
                micCreated++
                object : VoiceInput {
                    override fun start() { assertEquals(expected, received.toString()); micStarted = true }
                    override fun read(): ByteArray { Thread.sleep(10); return byteArrayOf(0, 0) }
                    override fun close() { inputClosed = true }
                }
            }
            coordinator.start(input); idle(coordinator)
            assertEquals(VoiceReadiness.COVERAGE_GAP.feedback, coordinator.state.value.error)
            assertEquals(0, pauses); assertEquals(0, connections); assertEquals(0, micCreated)
            val past = (0 until 30).map { SegmentEntity("ep", "rev", it * 10000L, (it + 1) * 10000L,
                "Synthetic passage $it " + "text ".repeat(250)) }
            dao.putSegments(past + listOf(SegmentEntity("ep", "rev", 299000, 301000, "Future straddling speech"),
                SegmentEntity("ep", "old", 290000, 300000, "Old revision")))
            expected = VoiceContext.load(db, "ep", 300000)
            assertTrue(expected.length > 12000)
            val spans = JSONObject(expected).getJSONArray("sourceSegments")
            assertEquals(past.size, spans.length())
            assertEquals(past.first().text, spans.getJSONObject(0).getString("text"))
            coordinator.start(input); idle(coordinator)
            assertNull(coordinator.state.value.error)
            assertEquals(1, pauses); assertEquals(1, connections); assertEquals(1, micCreated); assertEquals(1, restored)
            assertTrue(micStarted); assertTrue(inputClosed); assertEquals(1, answerChunks)
        } finally { server.shutdown(); db.close() }
    }
    @Test fun `cancelling full source upload closes resources before restoring lease`() = runBlocking { uploadExit(false) }
    @Test fun `provider capacity is distinct from readiness and restores lease before recording`() = runBlocking { uploadExit(true) }

    private suspend fun CoroutineScope.uploadExit(rejected: Boolean) {
        val server = MockWebServer()
        val uploadStarted = CompletableDeferred<Unit>()
        val closed = mutableListOf<String>()
        var recording = false
        var reports = 0
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(socket: WebSocket, response: Response) { socket.send("""{"type":"session.created"}""") }
            override fun onMessage(socket: WebSocket, text: String) {
                when (JSONObject(text).getString("type")) {
                    "session.update" -> socket.send("""{"type":"session.updated"}""")
                    "conversation.item.create" -> {
                        uploadStarted.complete(Unit)
                        if (rejected) socket.send("""{"type":"error","error":{"code":"context_length_exceeded","message":"private-provider-sentinel"}}""")
                    }
                }
            }
        }))
        try {
            val coordinator = VoiceCoordinator(this, { object : VoiceLease {
                override val episode = "ep"; override val position = 300000L
                override suspend fun finish(resume: Boolean) { assertTrue(resume); closed += "lease" }
            } }, { "Synthetic full source ".repeat(2000) }, { source ->
                val delegate = RealtimeVoice(source, "synthetic-fixture-key", ready = {}, endpoint = server.url("/realtime").toString())
                object : VoiceConnection {
                    override suspend fun answer(input: VoiceInput, output: VoiceOutput, phase: (String) -> Unit, text: (String) -> Unit) =
                        delegate.answer(input, output, phase, text)
                    override fun close() { delegate.close(); closed += "connection" }
                }
            }, { object : VoiceOutput {
                override suspend fun play(pcm: ByteArray) { fail("No answer before source upload finishes") }
                override suspend fun drain() { fail("No answer before source upload finishes") }
                override fun close() { closed += "output" }
            } }, diagnosticFailure = { reports++; assertTrue(it is VoiceSetupFailure) })
            coordinator.start({ object : VoiceInput {
                override fun start() { recording = true }
                override fun read(): ByteArray? = error("Recording must not start")
                override fun close() { closed += "input" }
            } }, prepareAudio = { AutoCloseable { closed += "route" } })
            withTimeout(5000) { uploadStarted.await() }
            if (!rejected) coordinator.cancel(true)
            idle(coordinator)
            assertFalse(recording)
            assertEquals(listOf("input", "connection", "output", "route", "lease"), closed)
            assertEquals(if (rejected) 1 else 0, reports)
            if (rejected) {
                assertTrue(coordinator.state.value.error!!.contains("context limit"))
                assertFalse(coordinator.state.value.error!!.contains("private-provider-sentinel"))
                assertFalse(VoiceReadiness.entries.any { it.feedback == coordinator.state.value.error })
            } else assertNull(coordinator.state.value.error)
        } finally { server.shutdown() }
    }
}
