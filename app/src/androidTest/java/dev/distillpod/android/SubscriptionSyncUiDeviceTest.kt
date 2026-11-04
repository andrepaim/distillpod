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
    @Test fun settingsExplainSubscriptionScopeAndKeepGoogleSeparateFromChatGpt()=runBlocking<Unit> {
        val graph=compose.activity.applicationContext.appGraph
        graph.ready.await()
        org.junit.Assert.assertFalse("Instrumentation must disable automatic production sync",graph.syncAccount.state.value.configured)
        withContext(Dispatchers.Main) {
            ViewModelProvider(compose.activity,AppViewModel.factory(graph))[AppViewModel::class.java].root("settings")
        }
        compose.onNodeWithText("Subscription sync").assertIsDisplayed()
        compose.onNodeWithText("Connect Google to sync public podcast subscriptions between your phones. Listening progress, saved moments and transcripts still use Backup & Restore in this version.").assertIsDisplayed()
        if(graph.syncAccount.state.value.configured) {
            compose.onNodeWithText("Connect Google & sync subscriptions").assertIsDisplayed()
        } else compose.onNodeWithText("Cloud sync is unavailable in this build.").assertIsDisplayed()
        compose.onNodeWithText("ChatGPT").performScrollTo().assertIsDisplayed()
    }
}
