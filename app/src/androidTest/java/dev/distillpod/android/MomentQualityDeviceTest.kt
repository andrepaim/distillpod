package dev.distillpod.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.distillpod.android.ai.*
import dev.distillpod.android.data.*
import dev.distillpod.android.features.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MomentQualityDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun takeawayOnlyCardSourceAndPreviousVersionRemainUsable()=runBlocking<Unit> {
        val graph=compose.activity.applicationContext.appGraph;graph.ready.await()
        val dao=graph.db.library();val prefix="moment-quality-${UUID.randomUUID()}"
        val vm=withContext(Dispatchers.Main) { ViewModelProvider(compose.activity,AppViewModel.factory(graph))[AppViewModel::class.java] }
        var updated=false
        val gateway=object:AiGateway {
            override suspend fun complete(instructions:String,input:String,onDelta:(String)->Unit)=AiText(
                if(instructions==MomentGeneration.reviewPrompt) """{"passageComplete":true,"takeawaySupported":true,"relevant":true,"quoteFaithful":true,"needsContext":false,"issues":[]}"""
                else """{"outcome":"ready","passageStart":0,"passageEnd":0,"takeaway":"${if(updated)"Frequent practice supports steady progress." else "Short daily practice helps build a lasting skill."}","quoteStart":null,"quoteEnd":null}""","test-model")
        }
        try {
            dao.putPodcast(PodcastEntity(prefix,"https://example.test/$prefix","Synthetic moments"))
            dao.putEpisode(EpisodeEntity(prefix,prefix,prefix,"Moment quality fixture","https://example.test/audio",durationMs=120000,mediaFingerprint=prefix))
            dao.putTranscript(TranscriptEntity(prefix,"r",prefix,"publisher","en",source="publisher",state="ready",durationMs=120000))
            dao.putCoverage(CoverageEntity(prefix,"r",0,120000))
            dao.putSegments(listOf(SegmentEntity(prefix,"r",40000,60000,"Short daily practice helps build a lasting skill.")))
            val repo=CaptureRepository(graph.db,gateway,{true},{error("Coverage complete")},{})
            val id=repo.capture(prefix,60000,true);repo.run(id)
            withContext(Dispatchers.Main) { vm.root("saved") }
            compose.waitUntil(10000) { compose.onAllNodesWithText("Short daily practice helps build a lasting skill.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Short daily practice helps build a lasting skill.").performScrollTo().performClick()
            compose.onNodeWithText("Saved as a takeaway. No suitable standalone quote was selected.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Replay source · 0:40").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Read source passage").performScrollTo().performClick()
            updated=true;repo.improve(id);repo.run(id)
            compose.onNodeWithText("Previous versions (1)").performScrollTo().performClick()
            compose.onNodeWithText("Restore this version").performScrollTo().performClick()
            withTimeout(5000) { while(dao.distillation(id)?.insight!="Short daily practice helps build a lasting skill.")delay(50) }
            assertNull(dao.distillation(id)!!.quote)
            assertEquals(60000L,dao.distillation(id)!!.endMs)
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Short daily practice helps build a lasting skill.").fetchSemanticsNodes().isNotEmpty() }
        } finally { withContext(Dispatchers.Main){vm.root("library")};dao.deletePodcast(prefix) }
    }
}
