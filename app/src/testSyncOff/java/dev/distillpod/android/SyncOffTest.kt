package dev.distillpod.android

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.android.features.AppViewModel
import dev.distillpod.android.features.SettingsOverview
import dev.distillpod.android.features.SyncPage
import dev.distillpod.android.sync.SUBSCRIPTION_SYNC_RECOVERY_WORK
import dev.distillpod.android.sync.SUBSCRIPTION_SYNC_WORK
import dev.distillpod.android.sync.SubscriptionCloudAccess
import dev.distillpod.android.sync.SubscriptionSyncAccount
import dev.distillpod.android.sync.SyncBuild
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The Play preset builds without library sync (#323): no Google sign-in, no Firebase and no account, so
 * there is no account to delete. These tests run only in that build (src/testSyncOff).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncOffTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val application get() = ApplicationProvider.getApplicationContext<Application>()
    private val manager get() = WorkManager.getInstance(application)
    private lateinit var graph: AppGraph
    private lateinit var vm: AppViewModel
    private val store = ViewModelStore()

    /** Stands in for the sync worker an earlier GitHub build scheduled; this build has none. */
    class EarlierBuildWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork() = Result.success()
    }

    @Before fun setup() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(application,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        shadowOf(application).declareComponentUnbindable(ComponentName(application, PlaybackService::class.java))
        graph = application.appGraph
        graph.scope.cancel()
        withTimeout(5000) { while (!graph.scope.coroutineContext[Job]!!.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle(); delay(10)
        } }
        vm = AppViewModel(graph, SavedStateHandle())
        store.put("sync-off", vm)
    }

    @After fun cleanup() = runBlocking {
        val viewModelJob = if (::vm.isInitialized) vm.viewModelScope.coroutineContext.job else null
        store.clear()
        manager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
        viewModelJob?.awaitViewModelTeardown()
        graph.db.close()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun work(name: String) = manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS)

    @Test fun `the build contains no Firebase, Google sign-in, sync transport, worker or sync settings`() {
        assertFalse(SyncBuild.ENABLED)
        for (name in listOf("com.google.firebase.FirebaseApp", "com.google.firebase.auth.FirebaseAuth",
            "com.google.firebase.firestore.FirebaseFirestore", "com.google.firebase.storage.FirebaseStorage",
            "androidx.credentials.CredentialManager", "com.google.android.libraries.identity.googleid.GoogleIdTokenCredential",
            "dev.distillpod.android.sync.FirebaseSubscriptionCloud", "dev.distillpod.android.sync.FirebaseContentObjects",
            "dev.distillpod.android.sync.SubscriptionSyncWorker", "dev.distillpod.android.features.PublicFeedReviewKt")) {
            assertThrows(name, ClassNotFoundException::class.java) { Class.forName(name) }
        }
    }

    @Test fun `sync is never configured and the account can never connect`() {
        assertFalse(SubscriptionCloudAccess.configured(application))
        val state = graph.syncAccount.state.value
        assertFalse(state.configured)
        assertFalse(state.connected)
        assertFalse(graph.syncAccount.contentConfigured)
        assertFalse(graph.syncAccount.signedIn("alice"))
    }

    @Test fun `sync work left by an earlier GitHub build is cancelled and nothing is scheduled again`() = runBlocking {
        manager.enqueueUniquePeriodicWork(SUBSCRIPTION_SYNC_RECOVERY_WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<EarlierBuildWorker>(15, TimeUnit.MINUTES).build()).result.get(5, TimeUnit.SECONDS)
        manager.enqueueUniqueWork(SUBSCRIPTION_SYNC_WORK, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<EarlierBuildWorker>().setInitialDelay(1, TimeUnit.DAYS).build()).result.get(5, TimeUnit.SECONDS)
        val account = SubscriptionSyncAccount(graph)
        account.kick(); account.syncNow()
        assertTrue(account.syncOnce())
        for (name in listOf(SUBSCRIPTION_SYNC_RECOVERY_WORK, SUBSCRIPTION_SYNC_WORK))
            assertEquals(name, listOf(WorkInfo.State.CANCELLED), work(name).map { it.state })
    }

    @Test fun `Settings Sync shows automatic backup and no cloud account, Google sign-in or deletion`() {
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { SyncPage(vm) } } }
        compose.onNodeWithText("Automatic backup").assertExists()
        compose.onNodeWithContentDescription("Learn more about What's covered").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Restore from a backup to bring back your library", substring = true).assertExists()
        for (text in listOf("Cloud sync", "Connect Google & sync library", "Sign out of sync", "Pause sync",
            "Delete sync account and cloud data"))
            compose.onAllNodesWithText(text).assertCountEquals(0)
        for (text in listOf("Google account", "Firebase", "sign in to sync"))
            compose.onAllNodesWithText(text, substring = true, ignoreCase = true).assertCountEquals(0)
    }

    @Test fun `the Settings Sync row does not offer a Google account`() {
        compose.setContent { MaterialTheme { SettingsOverview(connected = false, voiceEnabled = false) {} } }
        compose.onAllNodesWithText("Sync").onFirst().assertExists()
        compose.onAllNodesWithText("Google account", substring = true).assertCountEquals(0)
    }
}
