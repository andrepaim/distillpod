package dev.distillpod.android

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
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
import dev.distillpod.android.features.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SearchDesignDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun audioAndVideoFiltersKeepPendingProviderVisible() = runBlocking<Unit> {
        val rows = listOf(PodcastCandidate("Audio fixture", "Publisher", "https://example.test/audio", null),
            PodcastCandidate("Video fixture", "Publisher", "https://www.youtube.com/@fixture/videos", null, "YouTube · channel"))
        val state = mutableStateOf(SearchState(results = rows.take(1), loading = true, submittedQuery = "fixture", loadingProviders = setOf("youtube")))
        withContext(Dispatchers.Main) { compose.activity.setContent {
            DistillTheme { Surface { SearchScreen("fixture", state.value, emptyList(), emptyList(), emptySet(),
                {}, {}, {}, {}, {}, {}) } }
        } }
        compose.onNodeWithText("Video", substring = false).performClick()
        compose.onNodeWithText("Audio fixture").assertDoesNotExist()
        compose.onNodeWithText("Searching YouTube…").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(results = rows, loading = false, loadingProviders = emptySet()) }
        compose.onNodeWithText("Video fixture").assertIsDisplayed()
        compose.onNodeWithText("Audio fixture").assertDoesNotExist()
        compose.onNodeWithText("Audio", substring = false).performClick()
        compose.onNodeWithText("Audio fixture").assertIsDisplayed()
        compose.onNodeWithText("Video fixture").assertDoesNotExist()
        compose.onNodeWithText("All", substring = false).performClick()
        compose.onNodeWithText("Audio fixture").assertIsDisplayed()
        compose.onNodeWithTag("Podcast search results").performScrollToNode(hasText("Video fixture"))
        compose.onNodeWithText("Video fixture").assertIsDisplayed()
    }

    @Test fun partialProviderResultsStayVisibleAndRetryTargetsOnlyTheFailedProvider() = runBlocking<Unit> {
        val state = mutableStateOf(SearchState(results = listOf(PodcastCandidate("Fast podcast", "Fixture", "https://example.test/feed", null)),
            loading = true, submittedQuery = "science", loadingProviders = setOf("youtube")))
        var retried: String? = null
        withContext(Dispatchers.Main) { compose.activity.setContent {
            DistillTheme { Surface { SearchScreen("science", state.value, emptyList(), emptyList(), emptySet(),
                onQuery = {}, onSearch = { error("Provider retry must not resubmit the complete search") }, onClearRecent = {},
                onFollow = {}, onOpenPodcast = {}, onExplore = {}, onRetryProvider = { retried = it }) } }
        } }
        compose.onNodeWithText("Fast podcast").assertIsDisplayed()
        compose.onNodeWithTag("Podcast search results").performScrollToNode(hasText("Searching YouTube…"))
        compose.onNodeWithText("Searching YouTube…").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(loading = false, loadingProviders = emptySet(), failedProviders = setOf("youtube")) }
        compose.onNodeWithTag("Podcast search results").performScrollToNode(hasText("Retry"))
        compose.onNodeWithText("Retry").performClick()
        assertEquals("youtube", retried)
    }

    @Test fun thumbnailPreviewFollowRetryHistoryRssAndLargeText() = runBlocking<Unit> {
        val graph = compose.activity.applicationContext.appGraph
        graph.ready.await()
        val server = MockWebServer()
        val id = UUID.randomUUID().toString()
        val images = AtomicInteger()
        val failures = AtomicInteger()
        val png = ByteArrayOutputStream().also { output ->
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.rgb(19, 140, 128)); compress(Bitmap.CompressFormat.PNG, 100, output); recycle() }
        }.toByteArray()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/cover.png" -> { images.incrementAndGet(); MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png)) }
                "/directory" -> {
                    val term = request.requestUrl?.queryParameter("term")
                    if (term == "offline" && failures.getAndIncrement() == 0) MockResponse().setResponseCode(503)
                    else if (term == "nothing") MockResponse().setBody("{\"results\":[]}")
                    else MockResponse().setBodyDelay(150, TimeUnit.MILLISECONDS).setBody("""{"results":[{"collectionName":"Signal and Story","artistName":"Studio North","feedUrl":"${server.url("/feed-$id")}","artworkUrl600":"${server.url("/cover.png")}","primaryGenreName":"Science"},{"collectionName":"Quiet Ideas","artistName":"Another creator","feedUrl":"${server.url("/other-$id")}"}]}""")
                }
                "/feed-$id" -> MockResponse().setBody("""<rss><channel><title>Signal and Story</title><image><url>${server.url("/cover.png")}</url></image><item><guid>$id</guid><title>A useful episode</title><enclosure url="${server.url("/clip.wav")}" type="audio/wav"/></item></channel></rss>""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = PodcastRepository(graph.db, graph.http, server.url("/directory"))
        val draft = mutableStateOf("")
        val recent = mutableStateOf(listOf("History"))
        val followed = mutableStateOf(emptyList<PodcastEntity>())
        val font = mutableStateOf(1f)
        var created: String? = null
        var opened: String? = null
        val search = PodcastSearch(scope, repository::search, { recent.value = (listOf(it) + recent.value).distinct().take(6) })
        val folder = File(graph.context.filesDir, "validation").apply { mkdirs() }
        fun screenshot(name: String) {
            compose.waitForIdle()
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle(1000)
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(folder, "search-$name.png"))
        }
        try {
            withContext(Dispatchers.Main) { compose.activity.setContent {
                val state by search.state.collectAsState()
                CompositionLocalProvider(LocalDensity provides Density(graph.context.resources.displayMetrics.density, font.value)) {
                    DistillTheme { Surface { Box(Modifier.fillMaxSize().padding(top = 48.dp, bottom = 160.dp)) {
                        SearchScreen(draft.value, state, recent.value, followed.value, emptySet(),
                            { draft.value = it; search.clear() }, { draft.value = it; search.submit(it) }, { recent.value = emptyList() },
                            { url -> scope.launch { created = repository.subscribe(url); followed.value = listOf(graph.db.library().podcast(created!!)!!) } },
                            { opened = it }, {})
                    } } }
                }
            } }
            compose.onNodeWithText("Explore topics").assertIsDisplayed()
            compose.onNodeWithText("Science").performScrollTo().performClick()
            compose.waitUntil(10000) { search.state.value.results.size == 2 && images.get() > 0 }
            compose.waitUntil(5000) {
                val image = compose.onNodeWithTag("Search artwork Signal and Story", useUnmergedTree = true).captureToImage()
                val color = image.toPixelMap()[image.width / 2, image.height / 4]
                kotlin.math.abs(color.green - 140f / 255) < .03f && kotlin.math.abs(color.red - 19f / 255) < .03f
            }
            screenshot("results")
            compose.onNodeWithContentDescription("Preview Signal and Story").performClick()
            compose.onNodeWithContentDescription("Follow previewed podcast").assertIsDisplayed().performClick()
            compose.waitUntil(10000) { followed.value.isNotEmpty() }
            compose.onNodeWithText("Open podcast").performClick()
            assertEquals(created, opened)
            compose.onNodeWithContentDescription("Search podcasts").performTextReplacement("nothing")
            compose.onNodeWithContentDescription("Submit search").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("No podcasts found").fetchSemanticsNodes().isNotEmpty() }
            screenshot("empty")
            compose.onNodeWithContentDescription("Search podcasts").performTextReplacement("offline")
            compose.onNodeWithContentDescription("Submit search").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Search unavailable").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Try again").performClick()
            compose.waitUntil(10000) { search.state.value.results.size == 2 }
            withContext(Dispatchers.Main) { font.value = 1.6f }
            compose.onNodeWithContentDescription("Submit search").assertIsDisplayed()
            compose.onNodeWithContentDescription("Preview Signal and Story").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Open previewed podcast").performScrollTo().assertIsDisplayed()
            screenshot("large-preview")
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            compose.onNodeWithContentDescription("Clear search").performClick()
            compose.onNodeWithText("Recent searches").assertIsDisplayed()
            compose.onNodeWithText("Clear", substring = false).performClick()
            assertTrue(recent.value.isEmpty())
            compose.onNodeWithTag("Podcast search results").performScrollToNode(hasText("Add RSS feed"))
            compose.onNodeWithText("Add RSS feed").performClick()
            compose.onNodeWithTag("Feed address").performTextInput(server.url("/feed-$id").toString())
            compose.onNodeWithText("Add feed", substring = false).performScrollTo().performClick()
            compose.waitUntil(10000) { draft.value == server.url("/feed-$id").toString() && followed.value.size == 1 }
            assertEquals(created, graph.db.library().podcastByUrl(server.url("/feed-$id").toString())!!.id)
        } finally {
            // Let any feed operation finish before removing only the test's subscription.
            scope.coroutineContext[Job]?.children?.toList()?.forEach { it.join() }
            created?.let { graph.db.library().deletePodcast(it) }
            scope.cancel(); server.shutdown()
        }
    }

    @Test fun realDirectoryShowsPublisherThumbnails() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowPublicSearch") == "true")
        val graph = compose.activity.applicationContext.appGraph
        val history = graph.settings.value("podcastSearches").first()
        val vm = withContext(Dispatchers.Main) { ViewModelProvider(compose.activity, AppViewModel.factory(graph))[AppViewModel::class.java] }
        try {
            withContext(Dispatchers.Main) { vm.root("search"); vm.query("Naruhodo"); vm.search() }
            withTimeout(40000) { while (vm.search.value.loading || vm.search.value.submittedQuery == null) delay(100) }
            assertNull(vm.search.value.error)
            assertTrue(vm.search.value.results.isNotEmpty())
            assertTrue(vm.search.value.results.any { !it.imageUrl.isNullOrBlank() })
            compose.onNodeWithText("TOP RESULT").assertIsDisplayed()
            delay(2000)
            val directory = File(graph.context.filesDir, "validation").apply { mkdirs() }
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(directory, "search-public-results.png"))
            compose.onNodeWithContentDescription("Preview ${vm.search.value.results.first().title}").performClick()
            compose.onNode(hasContentDescription("Follow previewed podcast") or hasContentDescription("Open previewed podcast")).assertIsDisplayed()
            compose.waitForIdle()
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle(1000)
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(directory, "search-public-preview.png"))
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        } finally {
            // The public integration query must not become owner history.
            delay(500)
            graph.settings.text("podcastSearches", history)
        }
    }
}
