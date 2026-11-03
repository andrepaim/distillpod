package dev.distillpod.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FullEpisodeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun actualPublicEpisodeDownloadsDecodesAndCompletesAllBackgroundChunks() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowPublicEpisode") == "true")
        val graph = compose.activity.applicationContext.appGraph
        graph.ready.await()
        val dao = graph.db.library()
        val id = "full-episode-${UUID.randomUUID()}"
        val previous = graph.settings.flow.first()
        try {
            graph.settings.text("model", "tiny"); graph.settings.text("language", "en")
            val source = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("en-source.json").bufferedReader().use { it.readText() })
            dao.putPodcast(PodcastEntity(id, "https://example.test/$id", "Public episode validation", subscribed = false))
            dao.putEpisode(EpisodeEntity(id, id, id, "Public episode validation", source.getString("audio"), durationMs = 537000))
            val start = android.os.SystemClock.elapsedRealtime()
            graph.transcription.prepare(id, true)
            withTimeout(240000) {
                var last = -1L
                while (true) {
                    val row = dao.transcript(id)
                    if (row != null && row.processedMs != last) {
                        last = row.processedMs
                        android.util.Log.i("DistillPodValidation", "Full episode checkpoint: $last / ${row.durationMs} ms")
                    }
                    if (row?.state in listOf("ready", "failed", "waiting_model")) break
                    delay(200)
                }
            }
            val result = dao.transcript(id)!!
            assertEquals(result.error, "ready", result.state)
            assertTrue(result.durationMs > 300000)
            assertEquals(result.durationMs, result.processedMs)
            val segments = dao.segments(id, result.revision)
            val coverage = dao.coverage(id, result.revision)
            assertTrue(segments.size > 5 && coverage.size >= 3)
            assertTrue(segments.all { it.startMs >= 0 && it.endMs <= result.durationMs })
            File(graph.context.cacheDir, "full-episode-validation.json").writeText(JSONObject().put("date", "2026-11-02")
                .put("title", source.getString("title")).put("sourceDurationMs", result.durationMs)
                .put("processedMs", result.processedMs).put("chunks", coverage.size).put("segments", segments.size)
                .put("elapsedMs", android.os.SystemClock.elapsedRealtime() - start).put("downloadBytes", dao.episode(id)!!.downloadedBytes).toString(2))
        } finally {
            graph.transcription.pause(id)
            withTimeout(15000) { while (dao.episode(id)?.localFile?.let { graph.activeFiles.busy(it) } == true) delay(100) }
            graph.media.deleteDownload(id); dao.deletePodcast(id)
            graph.settings.text("model", previous.model); graph.settings.text("language", previous.language)
        }
    }
}
