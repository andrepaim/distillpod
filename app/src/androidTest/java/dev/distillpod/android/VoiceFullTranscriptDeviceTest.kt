package dev.distillpod.android

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.distillpod.android.data.*
import dev.distillpod.android.voice.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/** Disposable emulator only. Synthetic text/audio and a local fake provider; no microphone, account or paid request. */
@RunWith(AndroidJUnit4::class)
class VoiceFullTranscriptDeviceTest {
    @Test fun fullListenedDatabaseSnapshotReachesProviderBeforeRecording() = runBlocking {
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic"))
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        val server = MockWebServer()
        val completed = AtomicBoolean(false)
        var microphoneClosed = false
        val received = StringBuilder()
        val sourceParts = mutableListOf<String>()
        var session: JSONObject? = null
        try {
            val dao = db.library()
            dao.putPodcast(PodcastEntity("voice-source-fixture", "https://example.test/voice-source", "Synthetic show"))
            dao.putEpisode(EpisodeEntity("voice-episode", "voice-source-fixture", "guid", "Synthetic episode", "https://example.test/audio", mediaFingerprint = "current"))
            dao.putTranscript(TranscriptEntity("voice-episode", "current-revision", "current", "fixture", "pt", state = "running"))
            val past = (0 until 360).map { index ->
                SegmentEntity("voice-episode", "current-revision", index * 10_000L, (index + 1) * 10_000L,
                    "Synthetic passage $index: " + "explicação 🧠 \"quoted\"\n".repeat(15))
            }
            dao.putSegments(past + listOf(
                SegmentEntity("voice-episode", "old-revision", 0, 1000, "Excluded old revision"),
                SegmentEntity("voice-episode", "current-revision", 3_595_000, 3_605_000, "Excluded straddling speech"),
                SegmentEntity("voice-episode", "current-revision", 3_600_000, 3_610_000, "Excluded future speech")))
            val source = VoiceContext.load(db, "voice-episode", 3_600_000)
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(socket: WebSocket, response: Response) { socket.send("""{"type":"session.created"}""") }
                override fun onMessage(socket: WebSocket, text: String) {
                    val e = JSONObject(text)
                    when (e.getString("type")) {
                        "session.update" -> {
                            session = e.getJSONObject("session")
                            socket.send("""{"type":"session.updated"}""")
                        }
                        "conversation.item.create" -> {
                            val item = e.getJSONObject("item")
                            val content = item.getJSONArray("content").getJSONObject(0)
                            received.append(content.getString("text").substringAfter("\n"))
                            sourceParts += text
                            socket.send(JSONObject().put("type", if (sourceParts.size % 2 == 0) "conversation.item.created" else "conversation.item.done")
                                .put("item", item).toString())
                        }
                        "input_audio_buffer.append" -> if (!completed.get()) {
                            socket.send("""{"type":"input_audio_buffer.speech_started"}""")
                            socket.send("""{"type":"input_audio_buffer.speech_stopped"}""")
                        }
                        "response.create" -> {
                            completed.set(true)
                            socket.send("""{"type":"response.output_audio.delta","delta":"AAA="}""")
                            socket.send("""{"type":"response.output_audio_transcript.delta","delta":"Synthetic answer"}""")
                            socket.send("""{"type":"response.done","response":{"status":"completed","output":[]}}""")
                        }
                    }
                }
            }))
            var answer = ""; var drained = false; var audioChunks = 0
            val input = object : VoiceInput {
                override fun start() {
                    assertTrue("Full source must arrive before recording", source == received.toString())
                    val delivered = JSONObject(received.toString()).getJSONArray("sourceSegments")
                    assertEquals(past.size, delivered.length())
                    for (i in past.indices) {
                        assertEquals(past[i].startMs, delivered.getJSONObject(i).getLong("startMs"))
                        assertEquals(past[i].text, delivered.getJSONObject(i).getString("text"))
                    }
                }
                override fun read(): ByteArray { Thread.sleep(20); return byteArrayOf(0,0) }
                override fun close() { microphoneClosed = true }
            }
            val output = object : VoiceOutput {
                override suspend fun play(pcm: ByteArray) { assertTrue(microphoneClosed); audioChunks++ }
                override suspend fun drain() { drained = true }
                override fun close() {}
            }
            RealtimeVoice(source, "synthetic-fixture-key", ready = {}, endpoint = server.url("/realtime").toString()).use {
                withTimeout(15_000) { it.answer(input, output, {}, { delta -> answer += delta }) }
            }
            assertEquals("disabled", session!!.getString("truncation"))
            assertFalse(session!!.getString("instructions").contains("Synthetic passage"))
            assertTrue(sourceParts.size > 1)
            assertTrue(sourceParts.all { it.toByteArray(Charsets.UTF_8).size < 256000 })
            assertEquals("Synthetic answer", answer)
            assertEquals(1, audioChunks)
            assertTrue(drained)
        } finally { server.shutdown(); db.close() }
    }
}
