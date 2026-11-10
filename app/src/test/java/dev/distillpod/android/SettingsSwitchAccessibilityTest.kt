package dev.distillpod.android

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.android.features.AppViewModel
import dev.distillpod.android.features.DiagnosticsSettings
import dev.distillpod.android.features.VoiceSettings
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSwitchAccessibilityTest {
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
        store.put("settings", vm)
    }

    @After fun cleanup() = runBlocking {
        val viewModelJob = if (::vm.isInitialized) vm.viewModelScope.coroutineContext.job else null
        store.clear()
        WorkManager.getInstance(application).cancelAllWork().result.get(5, TimeUnit.SECONDS)
        viewModelJob?.awaitViewModelTeardown()
        graph.db.close()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test fun `diagnostics controls are independently named disabled switches`() {
        compose.setContent { MaterialTheme { DiagnosticsSettings(vm) } }
        for (label in listOf("Share diagnostics", "Share usage statistics")) {
            compose.onNode(hasText(label) and isToggleable()).assertExists().assertIsOff().assertIsNotEnabled()
        }
        compose.onAllNodes(isToggleable()).assertCountEquals(2)
    }
    @Test fun `rewind row is a named switch and persists its independent value`() {
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { VoiceSettings(vm) } } }
        val row=compose.onNode(hasText("Rewind after an answer") and isToggleable())
        row.assertIsOff().performScrollTo().performClick().assertIsOn()
        assertTrue(application.getSharedPreferences("voice", Context.MODE_PRIVATE).getBoolean("rewind",false))
        row.performClick().assertIsOff()
        assertFalse(application.getSharedPreferences("voice", Context.MODE_PRIVATE).getBoolean("rewind",true))
    }
    @Test fun `diagnostics labels remain single switch targets at two hundred percent font size`() {
        compose.setContent {
            val density=androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,2f)) {
                MaterialTheme { androidx.compose.foundation.layout.Box(Modifier.width(320.dp)) { DiagnosticsSettings(vm) } }
            }
        }
        for(label in listOf("Share diagnostics","Share usage statistics")) {
            compose.onNode(hasText(label) and isToggleable()).performScrollTo().assertIsDisplayed()
                .assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role,androidx.compose.ui.semantics.Role.Switch))
        }
        compose.onAllNodes(isToggleable()).assertCountEquals(2)
    }

}
