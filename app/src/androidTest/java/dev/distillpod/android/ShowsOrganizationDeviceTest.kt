package dev.distillpod.android

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.distillpod.android.data.*
import dev.distillpod.android.features.AppViewModel
import dev.distillpod.android.features.ProductApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Synthetic subscriptions only; never run on an owner's physical installation. */
@RunWith(AndroidJUnit4::class)
class ShowsOrganizationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var graph: AppGraph
    private lateinit var vm: AppViewModel
    private lateinit var original: ShowPreferences
    private val ids = listOf("shows-acceptance-a", "shows-acceptance-b", "shows-acceptance-c", "shows-acceptance-empty")
    private val titles = listOf("Shelf Alpha", "Shelf Beta", "Shelf Gamma", "Shelf Empty")

    @Before fun prepare() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Shows fixture requires an emulator" }
        graph = compose.activity.applicationContext.appGraph
        runBlocking {
            graph.ready.await()
            original = graph.settings.shows.first()
            graph.settings.showFormat(ShowFormat.ALL)
            graph.settings.showSort(ShowSort.TITLE)
            ids.forEachIndexed { i, id ->
                graph.db.library().putPodcast(PodcastEntity(id, "https://example.test/$id", titles[i], author = "Shelf creator",
                    refreshedAt = if (i == 0) 2000000000000 else 0))
                graph.settings.pinShow("https://example.test/$id", false)
            }
            graph.db.library().putEpisodes(listOf(
                episode("a", 0, "audio/mpeg", 1700000000000, played = 50),
                episode("b", 1, "video/mp4", 1700100000000, played = 100),
                episode("c-audio", 2, "audio/mpeg", 1700200000000),
                episode("c-video", 2, "video/mp4", 1700300000000, completed = true),
            ))
        }
        compose.runOnUiThread {
            vm = ViewModelProvider(compose.activity, AppViewModel.factory(graph))[AppViewModel::class.java]
            vm.root("shows")
        }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("shows-grid").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Search your shows").performTextInput("Shelf")
        compose.onNodeWithText("Search your shows").performImeAction()
    }

    @After fun clean() {
        if (!::original.isInitialized) return
        runBlocking {
            ids.forEach { id ->
                graph.db.library().deletePodcast(id)
                graph.settings.pinShow("https://example.test/$id", "https://example.test/$id" in original.pinnedFeeds)
            }
            graph.settings.showFormat(original.format)
            graph.settings.showSort(original.sort)
        }
    }

    @Test fun filtersSortPinsCountsAndRecreationUseTheRealLibraryAndPreferences() {
        chooseFormat(ShowFormat.VIDEO)
        compose.onNodeWithText("2 shows").assertIsDisplayed()
        compose.onNodeWithTag("show-card-${ids[0]}").assertDoesNotExist()
        compose.onNodeWithText("Audio & video").assertExists()
        chooseSort(ShowSort.LATEST_EPISODE)
        assertBefore(2, 1)
        compose.onNodeWithContentDescription("Pin Shelf Beta").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Unpin Shelf Beta").fetchSemanticsNodes().isNotEmpty() }
        assertBefore(1, 2)
        compose.onNode(hasText("1 unfinished") and hasAnyAncestor(hasTestTag("show-card-${ids[2]}")), useUnmergedTree = true).assertExists()
        runBlocking { graph.db.library().markCompleted("shows-episode-c-audio", true) }
        compose.waitUntil(5000) {
            compose.onAllNodes(hasText("0 unfinished") and hasAnyAncestor(hasTestTag("show-card-${ids[2]}")), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("video-pinned")
        // Pinning only changes local display preferences: opening the card remains separate.
        compose.onNodeWithText("Shelf Beta").performClick()
        compose.waitUntil(5000) { vm.backStack.value.last() == "podcast:${ids[1]}" }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Search your shows").assertTextContains("Shelf")
        // Activity recreation reattaches real settings and keeps the selected controls/pin.
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("show-format-VIDEO").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("show-format-VIDEO").assertIsSelected()
        compose.onNodeWithContentDescription("Sort shows: Latest episode").assertExists()
        compose.onNodeWithContentDescription("Unpin Shelf Beta").assertExists()
        chooseFormat(ShowFormat.AUDIO)
        compose.onNodeWithText("2 shows").assertExists()
        compose.onNodeWithTag("show-card-${ids[1]}").assertDoesNotExist()
        assertBefore(2, 0)
        chooseSort(ShowSort.RECENTLY_PLAYED)
        assertBefore(0, 2)
        chooseFormat(ShowFormat.ALL)
        chooseSort(ShowSort.TITLE)
        compose.onNodeWithContentDescription("Unpin Shelf Beta").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Pin Shelf Beta").fetchSemanticsNodes().isNotEmpty() }
        assertBefore(0, 1)
        compose.onNodeWithText("Search your shows").performTextReplacement("No such show")
        compose.onNodeWithText("Search your shows").performImeAction()
        compose.onNodeWithText("No matching shows").assertIsDisplayed()
        compose.onNodeWithText("Reset filters").performClick()
        compose.onNodeWithTag("show-format-ALL").assertIsSelected()
        compose.onNodeWithTag("shows-grid").performScrollToNode(hasText("No episodes yet"))
        compose.onNodeWithText("No episodes yet").assertIsDisplayed()
    }

    @Test fun narrowPageAtDoubleFontSizeKeepsAllControlsAndCardDetailsReachable() {
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(graph.context.resources.displayMetrics.density, 2f)) {
                    Box(Modifier.width(320.dp).fillMaxHeight()) { ProductApp(vm) }
                }
            }
        }
        chooseFormat(ShowFormat.VIDEO)
        chooseSort(ShowSort.LATEST_EPISODE)
        compose.onNodeWithTag("shows-grid").performScrollToNode(hasContentDescription("Pin Shelf Gamma"))
        compose.onNodeWithContentDescription("Pin Shelf Gamma").assertIsDisplayed().performClick()
        compose.onNodeWithTag("shows-grid").performScrollToNode(hasText("Shelf Gamma"))
        compose.onNodeWithText("Shelf Gamma").assertIsDisplayed()
        compose.onNodeWithTag("shows-grid").performScrollToNode(hasText("Audio & video"))
        compose.onNodeWithText("Audio & video").assertIsDisplayed()
        compose.onNodeWithTag("shows-grid").performScrollToNode(hasText("1 unfinished"))
        screenshot("large-text")
        compose.onNodeWithTag("shows-grid").performScrollToIndex(0)
        compose.onNodeWithText("Search your shows").performTextReplacement("No such show")
        compose.onNodeWithText("Search your shows").performImeAction()
        compose.onNodeWithTag("shows-grid").performScrollToNode(hasText("Reset filters"))
        compose.onNodeWithText("Reset filters").assertIsDisplayed().performClick()
        chooseFormat(ShowFormat.ALL)
    }

    private fun chooseFormat(format: ShowFormat) {
        compose.onNodeWithTag("shows-grid").performScrollToIndex(0)
        compose.onNodeWithTag("show-format-${format.name}").performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { graph.settings.shows.first().format == format } }
        compose.onNodeWithTag("show-format-${format.name}").assertIsSelected()
    }
    private fun chooseSort(sort: ShowSort) {
        compose.onNodeWithTag("shows-grid").performScrollToNode(hasTestTag("show-sort"))
        compose.onNodeWithTag("show-sort").performClick()
        compose.onNode(hasText(sort.label) and hasAnyAncestor(isPopup())).performClick()
        compose.waitUntil(5000) { runBlocking { graph.settings.shows.first().sort == sort } }
    }
    private fun assertBefore(first: Int, second: Int) {
        compose.onNodeWithTag("shows-grid").performScrollToIndex(0)
        val a = compose.onNodeWithTag("show-card-${ids[first]}").fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithTag("show-card-${ids[second]}").fetchSemanticsNode().boundsInRoot
        assertTrue("Expected ${titles[first]} before ${titles[second]}", a.top < b.top || (a.top == b.top && a.left < b.left))
    }
    private fun episode(id: String, show: Int, mime: String, published: Long, played: Long = 0, completed: Boolean = false) =
        EpisodeEntity("shows-episode-$id", ids[show], id, "Fixture episode $id", "https://example.test/episode/$id",
            mimeType = mime, publishedAt = published, lastPlayedAt = played, completed = completed)
    private fun screenshot(name: String) {
        val dir = File(compose.activity.filesDir, "shows-acceptance").apply { mkdirs() }
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(dir, "$name.png"))
    }
}
