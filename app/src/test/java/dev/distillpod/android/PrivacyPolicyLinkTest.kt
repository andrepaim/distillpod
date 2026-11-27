package dev.distillpod.android

import android.app.Application
import android.content.ComponentName
import android.content.Intent
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
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.android.ai.AccountState
import dev.distillpod.android.data.Preferences
import dev.distillpod.android.features.AppViewModel
import dev.distillpod.android.features.DiagnosticsSettings
import dev.distillpod.android.features.PrivacyPolicyUrl
import dev.distillpod.android.features.SettingsPage
import dev.distillpod.android.features.SyncPage
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** #325: Google Play requires the hosted privacy policy one tap away inside the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivacyPolicyLinkTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val application get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var graph: AppGraph
    private lateinit var vm: AppViewModel
    private val store = ViewModelStore()

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
        store.put("privacy", vm)
    }

    @After fun cleanup() = runBlocking {
        val viewModelJob = if (::vm.isInitialized) vm.viewModelScope.coroutineContext.job else null
        store.clear()
        WorkManager.getInstance(application).cancelAllWork().result.get(5, TimeUnit.SECONDS)
        viewModelJob?.awaitViewModelTeardown()
        graph.db.close()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun settings(section: String) = compose.setContent {
        MaterialTheme { SettingsPage(vm, Preferences(), AccountState(), emptyList(), emptyList(), section) }
    }

    private fun opensPolicy() {
        compose.onNodeWithText("Privacy policy").performScrollTo().assertIsDisplayed().assertHasClickAction().performClick()
        val started = shadowOf(application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(PrivacyPolicyUrl, started.dataString)
    }

    @Test fun `the policy address is the hosted page named in the store listing`() =
        assertEquals("https://andrepaim.github.io/distillpod/privacy.html", PrivacyPolicyUrl)

    @Test fun `about opens the hosted privacy policy in the browser`() {
        settings("about")
        opensPolicy()
    }

    @Test fun `connecting ChatGPT has the policy one tap away`() {
        settings("ai")
        compose.onNodeWithText("Continue with ChatGPT").assertExists()
        opensPolicy()
    }

    @Test fun `turning on diagnostics has the policy one tap away`() {
        compose.setContent { MaterialTheme { DiagnosticsSettings(vm) } }
        opensPolicy()
    }

    @Test fun `library sync has the policy one tap away`() {
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { SyncPage(vm) } } }
        opensPolicy()
    }

    @Test fun `a phone without a browser hears why instead of crashing`() {
        shadowOf(application).checkActivities(true)
        settings("about")
        compose.onNodeWithText("Privacy policy").performScrollTo().performClick()
        assertNull(shadowOf(application).nextStartedActivity)
        assertEquals("No app on this phone can open this link.", vm.messages.tryReceive().getOrNull())
    }
}
