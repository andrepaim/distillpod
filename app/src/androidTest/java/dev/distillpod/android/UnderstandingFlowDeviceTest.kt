package dev.distillpod.android

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelProvider
import dev.distillpod.android.features.AppViewModel
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.ai.*
import dev.distillpod.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UnderstandingFlowDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun summariesChatSourcesReviewedSkippingAndReplayUseOriginalAudio() = runBlocking {
        val graph = compose.activity.applicationContext.appGraph
        graph.ready.await()
        val dao = graph.db.library()
        val vm = withContext(Dispatchers.Main) {
            ViewModelProvider(compose.activity, AppViewModel.factory(graph))[AppViewModel::class.java]
                .also { it.root("library"); it.filter("all") }
        }
        val id = "understanding-check-${UUID.randomUUID()}"
        val previous = graph.settings.flow.first().skipAds
        val source = java.io.File.createTempFile("understanding-", ".wav", graph.context.cacheDir)
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("jfk.wav").use { input -> source.outputStream().use(input::copyTo) }
            dao.putPodcast(PodcastEntity(id, "https://example.test/$id", "Understanding validation show"))
            dao.putEpisode(EpisodeEntity(id, id, id, "Understanding validation episode", "https://example.test/audio", durationMs = 11000,
                localFile = source.absolutePath, downloadState = "downloaded", downloadedBytes = source.length(), mediaFingerprint = id))
            dao.putTranscript(TranscriptEntity(id, id, id, "publisher", "en", "publisher", "ready", 11000, 11000))
            dao.putCoverage(CoverageEntity(id, id, 0, 11000))
            dao.putSegments(listOf(SegmentEntity(id, id, 0, 2000, "A public speech asks people to contribute."),
                SegmentEntity(id, id, 2000, 5000, "A clearly labeled fixture sponsor message."), SegmentEntity(id, id, 5000, 11000, "People can contribute to their community.")))
            val ai = object : AiGateway { override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit): AiText =
                AiText(if (instructions.startsWith("Answer")) """{"answer":"The speaker asks people to contribute to their community.","supported":true,"sources":[5000]}"""
                else """{"summary":"A public speech calls for community contribution.","chapters":[{"startMs":0,"title":"Community"}],"ads":[{"startMs":2000,"endMs":5000,"reason":"Fixture sponsor message"}]}""", "device-fixture")
            }
            val repo = EpisodeUnderstanding(graph.db, ai, {}, {})
            repo.analyze(id); repo.runAnalysis(id)
            val reply = repo.ask(id, "What does the speaker ask?"); repo.runReply(reply)
            compose.waitUntil(10000) {
                vm.backStack.value.lastOrNull() == "library" && vm.filter.value == "all" &&
                    vm.episodes.value.any { it.id == id }
            }
            // Other retained fixtures/resume cards can put this row outside the lazy viewport.
            // Horizontal show chips also support scroll-to-node; only the vertical library owns it.
            compose.onNode(hasScrollToNodeAction() and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                .performScrollToNode(hasText("Understanding validation episode"))
            compose.onNodeWithText("Understanding validation episode").assertIsDisplayed().performClick()
            compose.onNodeWithText("Summary & chapters").performScrollTo().performClick()
            compose.onNodeWithText("A public speech calls for community contribution.").assertIsDisplayed()
            compose.onNodeWithText("Approve this range for skipping").performScrollTo()
            compose.onAllNodes(isToggleable())[0].performClick()
            await { org.json.JSONArray(dao.analysis(id)!!.adsJson).getJSONObject(0).optBoolean("approved") }
            graph.settings.flag("skipAds", true)
            graph.playback.play(id, 0)
            await { graph.playbackEvents.lastSkipped.value?.episodeId == id }
            assertEquals(5000L, graph.playbackEvents.lastSkipped.value!!.endMs)
            graph.playback.pause()
            graph.playbackEvents.lastSkipped.value = null
            graph.playback.play(id, 2200)
            delay(1200)
            assertNull("Explicit source replay must bypass skipping", graph.playbackEvents.lastSkipped.value)
            graph.playback.pause()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Ask this episode").performScrollTo().performClick()
            compose.onNodeWithText("The speaker asks people to contribute to their community.").assertIsDisplayed()
            compose.onNodeWithText("Source · 0:05").performClick()
            await { graph.playback.state.value.episodeId == id && graph.playback.state.value.positionMs >= 4900 }
            assertEquals(source.absolutePath, graph.playback.captureSource().third)
        } finally {
            graph.playback.pause(); graph.playback.remove(id)
            graph.settings.flag("skipAds", previous)
            graph.playbackEvents.lastSkipped.value = null
            dao.deletePodcast(id); source.delete()
        }
    }
    private suspend fun await(predicate: suspend () -> Boolean) = withTimeout(20000) { while (!predicate()) delay(100) }
}
