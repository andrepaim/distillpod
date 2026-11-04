package dev.distillpod.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.distillpod.android.features.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubscriptionSyncUiDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun sparkBuildKeepsContentStorageDisabled() {
        val context=compose.activity.applicationContext
        org.junit.Assert.assertFalse("A client bucket configuration must not enable paid content sync",context.resources.getBoolean(R.bool.sync_content_enabled))
        org.junit.Assert.assertFalse(context.appGraph.syncAccount.contentConfigured)
    }
    @Test fun fixtureChangesCannotLeaveDeferredProductionIntents()=runBlocking<Unit> {
        val context=compose.activity.applicationContext
        val name="cloud-guard-${java.util.UUID.randomUUID()}.db"
        val db=androidx.room.Room.databaseBuilder(context,dev.distillpod.android.data.AppDatabase::class.java,name).build()
        try {
            db.sync().putBinding(dev.distillpod.android.sync.SyncBindingEntity(uid="cached-owner",generation="g",enabled=true,lease="lease"))
            val store=dev.distillpod.android.sync.SubscriptionSyncStore(db)
            store.follow(dev.distillpod.android.data.PodcastEntity("fixture","https://example.test/fixture","Fixture"),true)
            org.junit.Assert.assertNotNull(db.library().podcast("fixture"))
            org.junit.Assert.assertEquals(0,store.pendingCount())
        } finally {db.close();context.deleteDatabase(name)}
    }
    @Test fun restoredIncompleteMomentStaysSavedUntilExplicitPreparation()=runBlocking<Unit> {
        val graph=compose.activity.applicationContext.appGraph
        graph.ready.await()
        val id="restored-ui-"+java.util.UUID.randomUUID().toString()
        try {
            graph.db.library().putPodcast(dev.distillpod.android.data.PodcastEntity(id,"https://example.test/$id","Restored source",subscribed=false))
            graph.db.library().putEpisode(dev.distillpod.android.data.EpisodeEntity(id,id,"guid","Restored episode","https://example.test/audio"))
            graph.db.library().putDistillation(dev.distillpod.android.data.DistillationEntity(id,id,0,10000,state="saved"))
            withContext(Dispatchers.Main) {ViewModelProvider(compose.activity,AppViewModel.factory(graph))[AppViewModel::class.java].open("capture:$id")}
            compose.onNodeWithText("Prepare saved moment").performScrollTo().assertIsDisplayed()
            org.junit.Assert.assertEquals("saved",graph.db.library().distillation(id)!!.state)
            org.junit.Assert.assertNull(graph.db.library().transcript(id))
        } finally {
            withContext(Dispatchers.Main) {ViewModelProvider(compose.activity,AppViewModel.factory(graph))[AppViewModel::class.java].root("library")}
            graph.db.library().deletePodcast(id)
        }
    }
    @Test fun settingsExplainSubscriptionScopeAndKeepGoogleSeparateFromChatGpt()=runBlocking<Unit> {
        val graph=compose.activity.applicationContext.appGraph
        graph.ready.await()
        org.junit.Assert.assertFalse("Instrumentation must disable automatic production sync",graph.syncAccount.state.value.configured)
        withContext(Dispatchers.Main) {
            ViewModelProvider(compose.activity,AppViewModel.factory(graph))[AppViewModel::class.java].root("settings")
        }
        compose.onNodeWithText("Library sync").assertIsDisplayed().performClick()
        compose.onNodeWithText("Connect Google to sync public podcast subscriptions, listening progress, completion and saved moments between your phones. Transcripts and other library data still use Backup & Restore.").assertIsDisplayed()
        if(graph.syncAccount.state.value.configured) {
            compose.onNodeWithText("Connect Google & sync library").assertIsDisplayed()
        } else compose.onNodeWithText("Cloud sync is unavailable in this build.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("ChatGPT").performScrollTo().assertIsDisplayed()
    }
}
