package dev.distillpod.android

import androidx.compose.ui.test.*
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.distillpod.android.features.ProductApp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.distillpod.android.data.*
import dev.distillpod.android.features.AppViewModel
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DesignReviewDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private suspend fun open(route:String) {
        val graph=compose.activity.applicationContext.appGraph
        graph.ready.await()
        withContext(Dispatchers.Main) {
            val vm=ViewModelProvider(compose.activity,AppViewModel.factory(graph))[AppViewModel::class.java]
            if(route=="saved")vm.root(route) else vm.open(route)
        }
    }
    private fun shot(name:String) {
        compose.waitForIdle()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle(1000)
        val directory=File(compose.activity.filesDir,"design-review").apply {mkdirs()}
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(directory,"$name.png"))
    }
    @Test fun settingsExposeBackupWithoutScrolling()=runBlocking<Unit> {
        open("settings")
        shot("settings-before-check")
        compose.onNodeWithText("Backup & Restore").assertIsDisplayed().performClick()
        compose.onNodeWithText("Connect a backup file once. DistillPod remembers it for your next backup and restore.").assertIsDisplayed()
    }
    @Test fun settingsRemainReachableAt160Percent() = settingsNavigationAtFontScale(1.6f)
    @Test fun settingsRemainReachableAt200Percent() = settingsNavigationAtFontScale(2f)

    private fun settingsNavigationAtFontScale(scale: Float)=runBlocking<Unit> {
        val graph=compose.activity.applicationContext.appGraph
        graph.ready.await()
        lateinit var vm: AppViewModel
        withContext(Dispatchers.Main) {
            vm=ViewModelProvider(compose.activity,AppViewModel.factory(graph))[AppViewModel::class.java]
            vm.open("settings")
            compose.activity.setContent {CompositionLocalProvider(LocalDensity provides Density(graph.context.resources.displayMetrics.density,scale)){ProductApp(vm)}}
        }
        repeat(2) { pass ->
            compose.onNodeWithText("Backup & Restore").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Transcript preparation").performScrollTo().performClick()
            compose.onNodeWithText("Only transcribe while charging").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Download network policy").performScrollTo().performClick()
            compose.onNodeWithText("Wi-Fi / unmetered downloads only").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Only transcribe while charging").assertDoesNotExist()
            compose.onNodeWithText("Refresh subscriptions").assertDoesNotExist()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Português").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            // Match the settings card, not the bottom navigation Library tab.
            compose.onNode(hasText("Library") and hasText("Import, export and refresh subscriptions")).performScrollTo().performClick()
            compose.onNodeWithText("Refresh subscriptions").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Export OPML").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Moment saved sound").assertDoesNotExist()
            compose.onNodeWithText("Background progress").assertDoesNotExist()
            compose.onNodeWithText("Library sync").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Import OPML").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Notification access").performScrollTo().performClick()
            compose.onNodeWithText("Background progress").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Export OPML").assertDoesNotExist()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Playback & headphones").performScrollTo().performClick()
            compose.onNodeWithText("Moment saved sound").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Distill confirmation sound").assertDoesNotExist()
            shot("settings-font-" + scale + "-pass-" + pass)
            compose.onNodeWithContentDescription("Back").performClick()
        }
    }
    @Test fun gallerySearchFindsShowsBeyondTheRail() = gallerySearchScenario(false)
    @Test fun gallerySearchRetainsQueryAndClearsAtCompactSize() = gallerySearchScenario(true)

    private fun awaitUnique(matcher: SemanticsMatcher, description: String) {
        var count = -1
        try {
            compose.waitUntil(5000) {
                count = compose.onAllNodes(matcher).fetchSemanticsNodes().size
                count == 1
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("$description: expected one node, observed $count", failure)
        }
    }

    private fun showsSearchField(): SemanticsNodeInteraction {
        val grid = hasTestTag("shows-grid")
        awaitUnique(grid, "Shows grid readiness")
        compose.onNode(grid).performScrollToIndex(0)
        val field = hasSetTextAction() and hasAnyAncestor(grid)
        awaitUnique(field, "Shows editable search readiness")
        return compose.onNode(field).assertIsDisplayed().assertIsEnabled()
    }

    private fun gallerySearchScenario(compact: Boolean) = runBlocking<Unit> {
        val dao=compose.activity.applicationContext.appGraph.db.library()
        val ids=(0..29).map {"review-show-$it"}
        try {
            ids.forEachIndexed {i,id->dao.putPodcast(PodcastEntity(id,"https://example.test/$id","Review show ${i.toString().padStart(2,'0')}",author="Independent voices"))}
            if (compact) withContext(Dispatchers.Main) {
                val graph = compose.activity.applicationContext.appGraph
                val vm = ViewModelProvider(compose.activity, AppViewModel.factory(graph))[AppViewModel::class.java]
                compose.activity.setContent { Box(Modifier.requiredSize(320.dp, 400.dp)) { ProductApp(vm) } }
            }
            open("shows")
            val search = showsSearchField()
            compose.onNodeWithText("Search your shows", useUnmergedTree = true).assertExists()
            search.performTextInput("show 29")
            search.performImeAction()
            compose.onNodeWithText("1 show").assertIsDisplayed()
            compose.onNodeWithText("Review show 29").assertIsDisplayed()
            compose.onNodeWithText("Review show 00").assertDoesNotExist()
            shot("shows-search")
            compose.onNodeWithText("Review show 29").performClick()
            compose.onNodeWithContentDescription("Back").performClick()
            val restoredSearch = showsSearchField().assertTextContains("show 29")
            compose.onNodeWithContentDescription("Clear show search").performClick()
            restoredSearch.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            restoredSearch.performImeAction()
            compose.onNodeWithText("Review show 00").assertIsDisplayed()
            shot("shows")
        } finally {ids.forEach {dao.deletePodcast(it)}}
    }
    @Test fun readerAndSavedSearchMatchLocalContent()=runBlocking<Unit> {
        val dao=compose.activity.applicationContext.appGraph.db.library();val id="review-reading"
        try {
            dao.putPodcast(PodcastEntity(id,"https://example.test/$id","The Listening Room",author="Independent voices"))
            dao.putEpisode(EpisodeEntity(id,id,id,"The art of paying attention","https://example.test/audio",durationMs=120000))
            dao.putTranscript(TranscriptEntity(id,"review",id,"base","en",state="ready",durationMs=120000,processedMs=120000))
            dao.putSegments(listOf(SegmentEntity(id,"review",0,60000,"Attention is a practice we can build every day."),SegmentEntity(id,"review",60000,120000,"Curiosity gives us another way to listen.",1)))
            dao.putDistillation(DistillationEntity(id,id,0,60000,"Attention is a practice.",quote="Make room for curiosity.",insight="A small daily practice makes a difference.",state="succeeded"))
            open("transcript:$id")
            // Close segments read as one paragraph (#203), so passages are matched by substring.
            compose.onNodeWithText("Attention is a practice we can build every day.", substring = true).assertIsDisplayed()
            shot("transcript")
            compose.onNodeWithContentDescription("Search transcript").performClick()
            compose.onNodeWithText("Search transcript").performTextInput("curiosity")
            // Search moves between matching paragraphs instead of filtering them out.
            compose.onNodeWithText("1 of 1").assertIsDisplayed()
            compose.onNodeWithText("Curiosity gives us another way to listen.", substring = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("Clear transcript search").performClick()
            compose.onNodeWithText("Search transcript").performTextInput("zzzznoresult")
            compose.onNodeWithText("No matching passages").assertIsDisplayed()
            open("saved")
            compose.onNodeWithText("Search saved moments").performTextInput("curiosity")
            compose.onNodeWithText("Search saved moments").performImeAction()
            compose.onNodeWithText("“Make room for curiosity.”").assertIsDisplayed()
            shot("saved-search")
        } finally {dao.deletePodcast(id)}
    }
}
