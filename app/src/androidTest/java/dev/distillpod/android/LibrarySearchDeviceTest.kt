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

/** Disposable-emulator acceptance for #179: the Search field finds synthetic library content on the phone. */
@RunWith(AndroidJUnit4::class)
class LibrarySearchDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun searchFieldFindsEpisodesTranscriptsAndMomentsAndOpensThePassage() = runBlocking<Unit> {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic")) { "Fixture test is emulator-only" }
        val graph = compose.activity.applicationContext.appGraph; graph.ready.await()
        val dao = graph.db.library()
        val id = "library-search-${UUID.randomUUID()}"
        val word = "zephyrine"
        val directory = graph.context.getExternalFilesDir(null)!!
        fun screenshot(name: String) { compose.waitForIdle(); Thread.sleep(1000); UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(directory, "library-search-$name.png")) }
        val vm = withContext(Dispatchers.Main) { ViewModelProvider(compose.activity, AppViewModel.factory(graph))[AppViewModel::class.java] }
        try {
            dao.putPodcast(PodcastEntity(id, "https://example.test/$id", "The Synthetic Weather Hour"))
            dao.putEpisode(EpisodeEntity(id, id, id, "Winds named $word", "https://example.test/audio", durationMs = 1_800_000))
            dao.putEpisode(EpisodeEntity("$id-2", id, "$id-2", "Clouds and ice", "https://example.test/audio2", durationMs = 1_800_000))
            dao.putTranscript(TranscriptEntity("$id-2", "r1", "", "tiny", "en", state = "ready", durationMs = 1_800_000, processedMs = 1_800_000))
            dao.putSegments((0 until 80).map { n ->
                SegmentEntity("$id-2", "r1", n * 10_000L, n * 10_000L + 9_000, if (n == 61) "Then a $word wind crossed the valley." else "Synthetic passage number $n.")
            })
            dao.putDistillation(DistillationEntity("$id-moment", "$id-2", 30_000, 60_000, "Synthetic moment source.", null,
                "A $word is a warm wind that changes the season.", state = "succeeded"))
            withContext(Dispatchers.Main) { vm.root("search") }
            compose.onNodeWithContentDescription("Search your library and podcasts").performTextReplacement(word)
            compose.waitUntil(10000) { compose.onAllNodesWithText("Results from your library stay on this phone.").fetchSemanticsNodes().isNotEmpty() }
            // Typing searches only the phone; the directory is never queried without an explicit request.
            assertNull(vm.search.value.submittedQuery)
            // Scroll to rows by key (#197). performScrollToNode scrolls by deltas, which schedule
            // LazyColumn prefetch; work deferred to a later frame escapes Compose's idle sync, and its
            // main-thread composition can race this thread's semantics reads. Jumping to an index
            // schedules no prefetch (foundation 1.7.x).
            val results = compose.onNodeWithTag("Podcast search results")
            val episode = libraryRowKey("episodes", id); val passage = libraryRowKey("passages", "$id-2"); val moment = libraryRowKey("moments", "$id-moment")
            results.performScrollToKey(episode); compose.onNodeWithText("Winds named $word").assertIsDisplayed()
            screenshot("results")
            results.performScrollToKey(passage)
            compose.onNodeWithText("10:10 · 1 match").assertIsDisplayed()
            results.performScrollToKey(moment)
            compose.onNodeWithText("A $word is a warm wind", substring = true).assertIsDisplayed()
            results.performScrollToKey(DIRECTORY_PROMPT_KEY)
            compose.onNodeWithText("Search the podcast directory", substring = true).assertIsDisplayed()
            results.performScrollToKey(passage)
            compose.onNodeWithText("10:10 · 1 match", substring = true).performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("transcript-focus").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("transcript-focus").assertIsDisplayed()
            // Segments read as paragraphs (#203); the focused paragraph contains the matching segment.
            compose.onNodeWithText("Then a $word wind crossed the valley.", substring = true).assertIsDisplayed()
            screenshot("passage")
            withContext(Dispatchers.Main) { vm.back() }
            results.performScrollToKey(moment)
            compose.onNodeWithText("A $word is a warm wind", substring = true).performClick()
            compose.waitUntil(5000) { vm.backStack.value.last() == "capture:$id-moment" }
            withContext(Dispatchers.Main) { vm.back() }
            compose.onNodeWithContentDescription("Search your library and podcasts").performTextReplacement("no-such-$word")
            compose.waitUntil(10000) { compose.onAllNodesWithText("Nothing in your library matches", substring = true).fetchSemanticsNodes().isNotEmpty() }
            screenshot("empty")
            assertNull(vm.search.value.submittedQuery)
        } finally {
            withContext(Dispatchers.Main) { vm.query(""); vm.root("library") }
            dao.deletePodcast(id)
        }
    }
}
