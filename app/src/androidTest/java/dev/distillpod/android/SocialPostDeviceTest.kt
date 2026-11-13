package dev.distillpod.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import dev.distillpod.android.data.*
import dev.distillpod.android.features.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Disposable-emulator UI acceptance; completed content is explicitly a fixture. */
class SocialPostDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun twoFormatsAndReadableResultWithLargeText()=runBlocking<Unit> {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic")) { "Fixture test is emulator-only" }
        val graph=compose.activity.applicationContext.appGraph;graph.ready.await();val dao=graph.db.library()
        val id="social-device-${java.util.UUID.randomUUID()}";var postId:String?=null
        try {
            dao.putPodcast(PodcastEntity(id,"https://example.org/$id","Ideias do podcast"))
            dao.putEpisode(EpisodeEntity(id,id,id,"Prática e atenção","https://example.org/audio.mp3"))
            dao.putDistillation(DistillationEntity(id,id,1000,60000,"Pequenos hábitos ajudam a aprender.","Pequenos hábitos","A prática consistente ajuda a aprender.",state="succeeded"))
            val vm=withContext(Dispatchers.Main) {ViewModelProvider(compose.activity,AppViewModel.factory(graph))[AppViewModel::class.java].also {
                it.root("saved");it.open("capture:$id");compose.activity.setContent {CompositionLocalProvider(LocalDensity provides Density(graph.context.resources.displayMetrics.density,1.6f)) {ProductApp(it)}}
            }}
            compose.onNodeWithContentDescription("More actions for this moment").performClick()
            compose.onNodeWithText("Create post").performClick()
            compose.onNodeWithText("Quote & discussion").assertExists()
            compose.onNodeWithText("Infographic").assertExists()
            compose.onNodeWithText("Quote & discussion").performClick()
            var created:PostDraftEntity?=null
            withTimeout(10000) {while(created==null) {created=dao.observePosts().first().firstOrNull {it.sourceMomentId==id};delay(50)}}
            postId=created!!.id
            graph.socialResults.cancel(postId)
            dev.distillpod.android.work.SocialPostJobs.cancel(graph.context,postId)
            val result=graph.socialResults.get(postId)!!
            dao.putPost(dao.post(postId)!!.copy(content=result.copy(stage="ready",artifact=SocialArtifact(quote="Pequenos hábitos",text="Uma prática pequena pode tornar o aprendizado mais acessível.\n\nA ideia convida a observar o que conseguimos repetir com atenção.",title="Prática e atenção")).json()))
            compose.waitUntil(10000) {compose.onAllNodesWithText("Share").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Share").assertIsDisplayed();compose.onNodeWithText("Change").assertIsDisplayed()
            compose.onNodeWithText("Headline").assertDoesNotExist();compose.onNodeWithText("Design & preview").assertDoesNotExist()
            compose.onNodeWithText("Change").performClick()
            compose.onNodeWithText("What should change?").assertExists()
            compose.onNodeWithText("Cancel").performClick()
            compose.waitUntil(5000) {compose.onAllNodesWithText("What should change?").fetchSemanticsNodes().isEmpty()}
            compose.waitForIdle()
            androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()).takeScreenshot(java.io.File(graph.context.getExternalFilesDir(null),"social-result-preview.png"))
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10000) {compose.onAllNodesWithText("Share").fetchSemanticsNodes().isNotEmpty()}
            assertEquals("ready",graph.socialResults.get(postId)!!.stage)
            dao.deleteDistillation(id);assertNull(dao.post(postId)!!.sourceMomentId)
        } finally {dao.observePosts().first().filter {it.sourceMomentId==id || it.id==postId}.forEach {dao.deletePost(it.id)};dao.deletePodcast(id)}
    }
}
