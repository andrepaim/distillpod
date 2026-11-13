package dev.distillpod.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.distillpod.android.data.*
import dev.distillpod.android.features.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Disposable-emulator acceptance for the Saved list and moment page, using synthetic moments only. */
@RunWith(AndroidJUnit4::class)
class SavedMomentsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun compactListAndMomentPageKeepActionsReachable() = runBlocking<Unit> {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic")) { "Fixture test is emulator-only" }
        val graph = compose.activity.applicationContext.appGraph; graph.ready.await()
        val dao = graph.db.library()
        val id = "saved-layout-${UUID.randomUUID()}"
        val directory = graph.context.getExternalFilesDir(null)!!
        // Software-rendered emulators can lag a frame behind navigation; wait before capturing.
        fun screenshot(name: String) { compose.waitForIdle(); Thread.sleep(1000); UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(directory, "saved-layout-$name.png")) }
        val vm = withContext(Dispatchers.Main) { ViewModelProvider(compose.activity, AppViewModel.factory(graph))[AppViewModel::class.java] }
        try {
            dao.putPodcast(PodcastEntity(id, "https://example.test/$id", "The Synthetic Listening Room"))
            dao.putEpisode(EpisodeEntity(id, id, id, "Attention, curiosity and the practice of listening", "https://example.test/audio", durationMs = 1_800_000))
            val now = System.currentTimeMillis()
            val insights = listOf(
                "A small, repeated practice of attention changes what we notice and which ideas we carry into the week.",
                "Curiosity works best when we leave room for the question before reaching for an answer.",
                "Listening slowly is a skill: the pause after a sentence is where understanding settles.")
            insights.forEachIndexed { index, insight ->
                dao.putDistillation(DistillationEntity("$id-$index", id, 40_000L + index * 60_000, 100_000L + index * 60_000,
                    "Synthetic source passage $index.", "Make room for curiosity before an answer.", insight, state = "succeeded", createdAt = now - index))
            }
            dao.putDistillation(DistillationEntity("$id-retry", id, 300_000, 360_000, state = "failed", error = "Synthetic failure.", createdAt = now - 10))
            withContext(Dispatchers.Main) { vm.root("saved") }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("saved-moment-card").fetchSemanticsNodes().size >= 2 }
            compose.onNodeWithText("Your posts").assertDoesNotExist()
            compose.onNodeWithText("Moments").assertIsSelected()
            // Finished moments carry no status chip; the navigation bar's own "Saved" label is not part of a card.
            compose.onAllNodes(hasTestTag("saved-moment-card") and hasText("Saved")).assertCountEquals(0)
            // The issue's reference phone (1080x2316 at its default 450 dpi) is about 823 dp tall.
            if (graph.context.resources.configuration.screenHeightDp >= 800) {
                val viewport = compose.onNodeWithTag("saved-list").getBoundsInRoot()
                val cards = compose.onAllNodesWithTag("saved-moment-card")
                for (index in 0..1) assertTrue("card $index is cut off", cards[index].getBoundsInRoot().bottom <= viewport.bottom)
            }
            screenshot("list")
            compose.onNodeWithTag("saved-list").performScrollToNode(hasText("Needs retry"))
            compose.onNodeWithText("Needs retry", useUnmergedTree = true).assertIsDisplayed()

            compose.onNodeWithTag("saved-list").performScrollToNode(hasText(insights[0], substring = true))
            compose.onNodeWithText(insights[0], substring = true).performClick()
            compose.onNodeWithText("Attention, curiosity and the practice of listening").assertIsDisplayed()
            compose.onNodeWithText("The Synthetic Listening Room · 0:40–1:40").assertIsDisplayed()
            listOf("Replay 0:40", "Share", "Copy").forEach { compose.onNodeWithText(it).assertIsDisplayed().assertHasClickAction() }
            listOf("Create post", "Explain this idea", "Delete saved moment").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
            screenshot("moment")
            compose.onNodeWithContentDescription("More actions for this moment").performClick()
            listOf("Create post", "Posts from this moment", "Explain this idea", "Delete saved moment").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
            screenshot("moment-menu")
            compose.onNodeWithText("Delete saved moment").performClick()
            compose.onNodeWithText("Delete this saved moment?").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            assertNotNull(dao.distillation("$id-0"))
            compose.onNodeWithText("Source passage").performScrollTo().performClick()
            compose.onNodeWithText("Synthetic source passage 0.").performScrollTo().assertIsDisplayed()

            withContext(Dispatchers.Main) { vm.root("saved") }
            compose.onNodeWithText("Posts").performClick()
            compose.onNodeWithText("Posts").assertIsSelected()
            compose.onNodeWithText("Search saved moments").assertDoesNotExist()
        } finally {
            withContext(Dispatchers.Main) { vm.root("library") }
            dao.deletePodcast(id)
        }
    }
}
