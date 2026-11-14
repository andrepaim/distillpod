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
    @Test fun createPostOpensTheCardResultWithLargeText()=runBlocking<Unit> {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic")) { "Fixture test is emulator-only" }
        val graph=compose.activity.applicationContext.appGraph;graph.ready.await();val dao=graph.db.library()
        val id="social-device-${java.util.UUID.randomUUID()}";var postId:String?=null
        val excerpt="Pequenos hábitos ajudam a aprender. Quando você repete um pouco todo dia, o hábito cresce sem esforço."
        try {
            dao.putPodcast(PodcastEntity(id,"https://example.org/$id","Ideias do podcast"))
            dao.putEpisode(EpisodeEntity(id,id,id,"Prática e atenção","https://example.org/audio.mp3"))
            dao.putDistillation(DistillationEntity(id,id,1000,60000,excerpt,"Pequenos hábitos","A prática consistente ajuda a aprender.",state="succeeded"))
            withContext(Dispatchers.Main) {ViewModelProvider(compose.activity,AppViewModel.factory(graph))[AppViewModel::class.java].also {
                it.root("saved");it.open("capture:$id");compose.activity.setContent {CompositionLocalProvider(LocalDensity provides Density(graph.context.resources.displayMetrics.density,1.6f)) {ProductApp(it)}}
            }}
            compose.onNodeWithContentDescription("More actions for this moment").performClick()
            compose.onNodeWithText("Create post").performClick()
            // Creation starts at once: there is no format choice.
            var created:PostDraftEntity?=null
            withTimeout(10000) {while(created==null) {created=dao.observePosts().first().firstOrNull {it.sourceMomentId==id};delay(50)}}
            postId=created!!.id
            for (old in listOf("What would you like to share?","Quote & discussion","Infographic")) compose.onNodeWithText(old).assertDoesNotExist()
            compose.waitUntil(10000) {compose.onAllNodesWithText("Post").fetchSemanticsNodes().isNotEmpty()}
            graph.socialResults.cancel(postId)
            dev.distillpod.android.work.SocialPostJobs.cancel(graph.context,postId)
            val result=graph.socialResults.get(postId)!!
            val card=CardContent(quote="Pequenos hábitos ajudam a aprender.",highlight="Pequenos hábitos",headline="Hábitos pequenos sustentam o aprendizado",
                pull="Quando você repete um pouco todo dia, o hábito cresce sem esforço.",takeawaysTitle="Aprender com constância",
                takeaways=listOf(CardTakeaway("Comece pequeno","Escolha um passo que caiba em qualquer dia."),CardTakeaway("Repita todo dia","Um pouco por dia faz o hábito crescer."),
                    CardTakeaway("Sem esforço extra","A repetição leve evita a resistência.")),topic="Hábitos")
            val caption="Hábitos pequenos sustentam o aprendizado.\n\nQuando você repete um pouco todo dia, o hábito cresce sem esforço.\n\n🎧 Ideias do podcast · Prática e atenção\n\n#habitos #aprendizado #pratica"
            dao.putPost(dao.post(postId)!!.copy(content=result.copy(stage="ready",artifact=SocialArtifact(text=caption,quote=card.quote,title=card.headline,card=card.json().toString())).json()))
            compose.waitUntil(10000) {compose.onAllNodesWithText("Share").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Share").assertIsDisplayed();compose.onNodeWithText("Change").assertIsDisplayed()
            for (style in listOf("Quote","Big idea","Takeaways")) compose.onNodeWithText(style).assertExists()
            compose.onNodeWithContentDescription("Ember look").assertExists();compose.onNodeWithContentDescription("Signal look").assertExists()
            compose.waitUntil(10000) {compose.onAllNodesWithContentDescription(cardDescription(card,CardContent.STYLE_QUOTE)).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Headline").assertDoesNotExist();compose.onNodeWithText("Design & preview").assertDoesNotExist()
            compose.onNodeWithText("Change").performClick()
            compose.onNodeWithText("What should change?").assertExists()
            compose.onNodeWithText("Cancel").performClick()
            compose.waitUntil(5000) {compose.onAllNodesWithText("What should change?").fetchSemanticsNodes().isEmpty()}
            compose.onNodeWithContentDescription("Signal look").performClick()
            compose.waitUntil(10000) {runBlocking {graph.socialResults.get(postId)!!.artifact!!.cardContent()!!.look}==CardContent.LOOK_SIGNAL}
            compose.waitForIdle()
            androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()).takeScreenshot(java.io.File(graph.context.getExternalFilesDir(null),"social-result-preview.png"))
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10000) {compose.onAllNodesWithText("Share").fetchSemanticsNodes().isNotEmpty()}
            assertEquals("ready",graph.socialResults.get(postId)!!.stage)
            dao.deleteDistillation(id);assertNull(dao.post(postId)!!.sourceMomentId)
        } finally {dao.observePosts().first().filter {it.sourceMomentId==id || it.id==postId}.forEach {dao.deletePost(it.id)};dao.deletePodcast(id)}
    }
}
