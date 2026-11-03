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

@RunWith(AndroidJUnit4::class)
class LiveUnderstandingDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun actualAccountCompletesSummaryAndGroundedReplyThroughPersistentWorkers() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowLiveAccount") == "true")
        val graph = compose.activity.applicationContext.appGraph
        graph.ready.await()
        val dao = graph.db.library()
        val id = "live-understanding-${UUID.randomUUID()}"
        try {
            assertTrue("The saved account connection is required", graph.account.state.value.connected)
            dao.putPodcast(PodcastEntity(id, "https://example.test/$id", "Public speech integration check"))
            dao.putEpisode(EpisodeEntity(id, id, id, "Public speech", "https://example.test/audio", durationMs = 11000, mediaFingerprint = id))
            dao.putTranscript(TranscriptEntity(id, id, id, "publisher", "en", "publisher", "ready", 11000, 11000))
            dao.putCoverage(CoverageEntity(id, id, 0, 11000))
            dao.putSegments(listOf(SegmentEntity(id, id, 0, 11000, "Ask not what your country can do for you, ask what you can do for your country.")))
            graph.understanding.analyze(id)
            await { dao.analysis(id)?.state !in listOf("queued", "running") }
            val analysis = dao.analysis(id)!!
            assertEquals(analysis.error, "succeeded", analysis.state)
            assertTrue(analysis.summary.length >= 5)
            val reply = graph.understanding.ask(id, "What does the speaker ask people to do?")
            await { dao.message(reply)?.state !in listOf("queued", "running") }
            val answer = dao.message(reply)!!
            assertEquals(answer.error, "succeeded", answer.state)
            assertTrue(answer.text.length >= 3)
            assertTrue(org.json.JSONArray(answer.sourcesJson).length() > 0)
        } finally {
            graph.jobs.cancelAnalysis(id)
            dao.messages().filter { it.episodeId == id }.forEach { graph.jobs.cancelReply(it.id) }
            dao.deletePodcast(id)
        }
    }
    private suspend fun await(predicate: suspend () -> Boolean) = withTimeout(120000) { while (!predicate()) delay(200) }
}
