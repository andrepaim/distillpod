package dev.distillpod.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.distillpod.android.features.AppViewModel
import dev.distillpod.android.features.DiagnosticsSettings
import dev.distillpod.android.features.VoiceSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual settings semantics; spoken TalkBack acceptance is a separate manual gate. */
@RunWith(AndroidJUnit4::class)
class SettingsSwitchAccessibilityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private suspend fun model(): AppViewModel {
        val context = compose.activity.applicationContext
        val emulator = Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic")
        check(emulator || context.packageName == "dev.distillpod.android.validation.accessibility21")
        if (!emulator) check(context.checkSelfPermission(Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED)
        val graph = context.appGraph
        graph.ready.await()
        check(!graph.diagnostics.configured) { "Use a validation build without remote reporting" }
        return withContext(Dispatchers.Main) {
            ViewModelProvider(compose.activity, AppViewModel.factory(graph))[AppViewModel::class.java]
        }
    }

    private fun show(content: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme { content() } } }
    }

    @Test fun diagnosticsHaveSingleNamedDisabledTargetsAtLargeText() = runBlocking<Unit> {
        val vm = model()
        show {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                DiagnosticsSettings(vm)
            }
        }
        for (label in listOf("Share diagnostics", "Share usage statistics")) {
            val row = compose.onNode(hasText(label) and isToggleable())
            row.performScrollTo().assertIsDisplayed().assertIsOff().assertIsNotEnabled()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            row.assertHeightIsAtLeast(48.dp)
        }
        compose.onAllNodes(isToggleable()).assertCountEquals(2)
        compose.onAllNodes(isToggleable(), useUnmergedTree = true).assertCountEquals(2)
    }

    @Test fun rewindRowTogglesOnceAndPersistsItsOwnPreference() = runBlocking<Unit> {
        val vm = model()
        val preferences = compose.activity.getSharedPreferences("voice", android.content.Context.MODE_PRIVATE)
        val existed = preferences.contains("rewind")
        val original = preferences.getBoolean("rewind", false)
        check(preferences.edit().putBoolean("rewind", false).commit())
        try {
            show { Column(Modifier.verticalScroll(rememberScrollState())) { VoiceSettings(vm) } }
            val row = compose.onNode(hasText("Rewind after an answer") and isToggleable())
            row.performScrollTo().assertIsDisplayed().assertIsOff().assertIsEnabled()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            row.assertHeightIsAtLeast(48.dp)
            row.performClick().assertIsOn()
            assertTrue(preferences.getBoolean("rewind", false))
            row.performClick().assertIsOff()
            assertFalse(preferences.getBoolean("rewind", true))
        } finally {
            val edit = preferences.edit()
            if (existed) edit.putBoolean("rewind", original) else edit.remove("rewind")
            check(edit.commit())
        }
    }
}
