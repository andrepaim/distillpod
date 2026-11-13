package dev.distillpod.android

import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.android.features.AppViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * With android:enableOnBackInvokedCallback, Android 13+ delivers Back (gesture or key) to
 * OnBackInvokedCallbacks instead of Activity.onBackPressed. Each nested surface must still close
 * first and only an empty back stack may leave the app. Synthetic library rows only, on an emulator.
 */
@RunWith(AndroidJUnit4::class)
class PredictiveBackDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun back() { device.pressBack(); compose.waitForIdle() }
    private fun await(what: String, observed: () -> String, condition: () -> Boolean) =
        try { compose.waitUntil(10000, condition) } catch (timeout: ComposeTimeoutException) { throw AssertionError("$what; observed ${observed()}", timeout) }
    private fun awaitStack(vm: AppViewModel, expected: List<String>) = await("back stack $expected", { "${vm.backStack.value}" }) { vm.backStack.value == expected }

    @Test fun backClosesNestedSurfacesBeforeScreensAndLeavesOnlyFromTheRoot() = runBlocking<Unit> {
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic")) { "Use a disposable emulator" }
        val graph = compose.activity.applicationContext.appGraph
        graph.ready.await()
        val vm = withContext(Dispatchers.Main) { ViewModelProvider(compose.activity, AppViewModel.factory(graph))[AppViewModel::class.java] }
        val tag = "predictive-back-" + UUID.randomUUID()
        val title = "Back fixture $tag"
        val dao = graph.db.library()
        try {
            dao.putPodcast(PodcastEntity(tag, "https://example.test/$tag", "Back show"))
            dao.putEpisode(EpisodeEntity("$tag-episode", tag, "guid", title, "https://example.test/$tag.mp3"))

            // A dialog closes before its screen; screens then unwind one at a time.
            withContext(Dispatchers.Main) { vm.root("library"); vm.open("settings"); vm.open("settings:about") }
            compose.onNodeWithText("View GPL license").performScrollTo().performClick()
            compose.onNodeWithText("GNU GPL version 3").assertIsDisplayed()
            back()
            compose.onNodeWithText("GNU GPL version 3").assertDoesNotExist()
            assertEquals(listOf("library", "settings", "settings:about"), vm.backStack.value)
            back(); awaitStack(vm, listOf("library", "settings"))
            back(); awaitStack(vm, listOf("library"))

            // A popup menu closes without leaving its screen.
            // Earlier suites can leave a Downloads/In progress filter; small screens compose rows lazily.
            withContext(Dispatchers.Main) { vm.filter("all"); vm.open("podcast:$tag") }
            await("the synthetic show's episode", { "${vm.backStack.value} ${vm.library.value?.let { "${it.podcastId == tag}/${it.filter}/${it.visible.size}" }}" }) {
                vm.library.value?.let { it.podcastId == tag && it.visible.any { ep -> ep.title == title } } == true
            }
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("More actions for $title"))
            compose.onNodeWithContentDescription("More actions for $title").performClick()
            compose.onNodeWithText("Add to queue").assertIsDisplayed()
            back()
            compose.onNodeWithText("Add to queue").assertDoesNotExist()
            assertEquals(listOf("library", "podcast:$tag"), vm.backStack.value)
            back(); awaitStack(vm, listOf("library"))

            // A modal sheet closes without leaving its screen.
            withContext(Dispatchers.Main) { vm.root("search") }
            compose.onNodeWithTag("Podcast search results").performScrollToNode(hasText("Add RSS feed"))
            compose.onNodeWithText("Add RSS feed").performClick()
            compose.onNodeWithText("Add a podcast feed").assertIsDisplayed()
            back()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Add a podcast feed").fetchSemanticsNodes().isEmpty() }
            assertEquals(listOf("search"), vm.backStack.value)

            // The full-screen player is a screen of its own.
            withContext(Dispatchers.Main) { vm.root("library"); vm.open("player") }
            compose.onNodeWithText("Now playing").assertIsDisplayed()
            back(); awaitStack(vm, listOf("library"))
            assertEquals(Lifecycle.State.RESUMED, compose.activityRule.scenario.state)

            // Only the root lets Back leave the app. The system default applies there: a launcher
            // task moves to the background, this instrumentation-started activity finishes.
            device.pressBack()
            withTimeout(10000) { while (compose.activityRule.scenario.state == Lifecycle.State.RESUMED) delay(50) }
            assertEquals(listOf("library"), vm.backStack.value)
        } finally {
            withContext(NonCancellable) { dao.deletePodcast(tag) }
        }
    }
}
