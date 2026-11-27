package dev.distillpod.android

import android.app.Application
import android.content.ComponentName
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.android.data.*
import dev.distillpod.android.features.AnalysisPage
import dev.distillpod.android.features.AppViewModel
import dev.distillpod.android.features.EpisodeChatPage
import dev.distillpod.android.features.ExplanationPage
import dev.distillpod.android.ui.theme.DistillTheme
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** #324: Episode insights, Ask this episode and Explain this idea each offer Report this result on their model-written text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReportResultPagesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val application get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var graph: AppGraph
    private lateinit var vm: AppViewModel
    private val store = ViewModelStore()
    private val episode = EpisodeEntity("ep", "show", "guid", "Rest and effort", "https://example.test/audio", mediaFingerprint = "fp")

    @Before fun setup() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(application,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        shadowOf(application).declareComponentUnbindable(ComponentName(application, PlaybackService::class.java))
        graph = application.appGraph
        graph.scope.cancel()
        withTimeout(5000) { while (!graph.scope.coroutineContext[Job]!!.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle(); delay(10)
        } }
        val dao = graph.db.library()
        dao.putPodcast(PodcastEntity("show", "https://example.test/feed", "Show"))
        dao.putEpisode(episode)
        dao.putTranscript(TranscriptEntity("ep", "r1", "fp", "base", "en", state = "ready"))
        vm = AppViewModel(graph, SavedStateHandle())
        store.put("report", vm)
    }

    @After fun cleanup() = runBlocking {
        val viewModelJob = if (::vm.isInitialized) vm.viewModelScope.coroutineContext.job else null
        store.clear()
        WorkManager.getInstance(application).cancelAllWork().result.get(5, TimeUnit.SECONDS)
        viewModelJob?.awaitViewModelTeardown()
        graph.db.close()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun reportOpensConfirmation(expected: String) {
        compose.waitUntil(5000) { compose.onAllNodes(hasText("Report this result") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Report this result") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Open email app").assertIsDisplayed()
        compose.onNode(hasText(expected, substring = true) and hasAnyAncestor(isDialog())).assertExists()
    }

    @Test fun `episode insights report the summary and chapters`() {
        runBlocking { graph.db.library().putAnalysis(AnalysisEntity("ep", "r1", "succeeded", summary = "An episode about rest.",
            chaptersJson = JSONArray().put(JSONObject().put("startMs", 0).put("title", "Opening")).toString(), model = "gpt-5.1")) }
        compose.setContent { DistillTheme { AnalysisPage(vm, episode) } }
        reportOpensConfirmation("An episode about rest.")
        compose.onNode(hasText("0:00 · Opening", substring = true) and hasAnyAncestor(isDialog())).assertExists()
    }

    @Test fun `each episode chat answer can be reported, but not the user's question`() {
        runBlocking {
            val dao = graph.db.library()
            dao.putMessage(MessageEntity("q", "ep", "user", "Why rest?", createdAt = 1, revision = "r1", fingerprint = "fp"))
            dao.putMessage(MessageEntity("a", "ep", "assistant", "Rest comes first, the guest says.", createdAt = 2, replyTo = "q",
                revision = "r1", fingerprint = "fp", model = "gpt-5.1"))
        }
        compose.setContent { DistillTheme { EpisodeChatPage(vm, episode) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Rest comes first, the guest says.").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Report this result") and hasClickAction()).assertCountEquals(1)
        reportOpensConfirmation("Rest comes first, the guest says.")
        compose.onNode(hasText("Why rest?", substring = true) and hasAnyAncestor(isDialog())).assertDoesNotExist()
    }

    @Test fun `an explanation can be reported`() {
        runBlocking {
            val dao = graph.db.library()
            dao.putDistillation(DistillationEntity("m", "ep", 0, 20_000, "Saved passage.", "Rest.", "Rest helps.", "r1", "fp", "succeeded"))
            dao.putResearch(ResearchEntity("x", "m", "succeeded", report = "The idea is that recovery is part of the work.",
                sourcesJson = JSONArray().put(JSONObject().put("kind", "transcript").put("model", "gpt-5.1")).toString()))
        }
        compose.setContent { DistillTheme { ExplanationPage(vm, "x") } }
        reportOpensConfirmation("The idea is that recovery is part of the work.")
    }
}
