package dev.distillpod.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Deliberate integration run only. Never requests or exports credentials. */
@RunWith(AndroidJUnit4::class)
class LiveCaptureDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun actualAccountCompletesValidatedQuoteAndInsightThroughWorker() = runBlocking {
        assumeTrue("Pass allowLiveAccount=true for this deliberate integration check", InstrumentationRegistry.getArguments().getString("allowLiveAccount") == "true")
        val graph = compose.activity.applicationContext.appGraph
        val dao = graph.db.library()
        val id = "live-capture-check-${UUID.randomUUID()}"
        val source = java.io.File.createTempFile("live-capture-", ".wav", graph.context.cacheDir)
        val text = "Ask not what your country can do for you, ask what you can do for your country."
        try {
            withTimeout(15000) { while (!graph.account.state.value.connected) delay(100) }
            if (InstrumentationRegistry.getArguments().getString("refreshLiveAccount") == "true") withContext(Dispatchers.IO) {
                // Exercise real rotation without editing expiry metadata or exposing either token.
                graph.account.session.accessToken(forceRefresh = true)
            }
            InstrumentationRegistry.getInstrumentation().context.assets.open("jfk.wav").use { input -> source.outputStream().use(input::copyTo) }
            dao.putPodcast(PodcastEntity(id, "https://example.test/$id", "Public speech integration check"))
            dao.putEpisode(EpisodeEntity(id, id, id, "Public speech", "https://example.test/audio", durationMs = 11000,
                localFile = source.absolutePath, downloadState = "downloaded", downloadedBytes = source.length(), mediaFingerprint = id))
            dao.putTranscript(TranscriptEntity(id, id, id, "publisher", "en", "publisher", "ready", 11000, 11000))
            dao.putCoverage(CoverageEntity(id, id, 0, 11000))
            dao.putSegments(listOf(SegmentEntity(id, id, 0, 11000, text)))
            val capture = graph.captures.capture(id, 10000, true)
            withTimeout(120000) { while (dao.distillation(capture)?.state in listOf("queued", "running", "reviewing")) delay(200) }
            val completed = dao.distillation(capture)!!
            assertEquals(completed.error, "succeeded", completed.state)
            completed.quote?.let { assertTrue(text.contains(it)) }
            assertTrue(completed.insight!!.length >= 10)
            assertFalse(completed.model.isNullOrBlank())
        } finally {
            dao.distillations().filter { it.episodeId == id }.forEach { graph.jobs.cancelCapture(it.id) }
            dao.deletePodcast(id); source.delete()
        }
    }
}
