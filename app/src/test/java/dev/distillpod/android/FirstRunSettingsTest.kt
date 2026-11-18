package dev.distillpod.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.PortablePreferences
import dev.distillpod.core.FirstRunSetup
import dev.distillpod.core.FirstRunStatus
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FirstRunSettingsTest {
    @Test fun setupAndDismissalSurviveRestartAndPortablePreferenceRestore() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File(context.cacheDir, "onboarding-test.preferences_pb")
        val pending = FirstRunSetup(FirstRunStatus.PENDING, cardDismissed = false)
        val done = FirstRunSetup(FirstRunStatus.DONE, cardDismissed = false)
        try {
            settingsSession(context, file) { settings ->
                assertNull(settings.firstRunSetup.first())
                assertEquals(pending, settings.initializeFirstRunSetup(pending))
                assertEquals(pending, settings.initializeFirstRunSetup(FirstRunSetup.ExistingInstall))
            }
            settingsSession(context, file) { settings ->
                assertEquals(pending, settings.firstRunSetup.first())
                assertEquals(done, settings.completeFirstRunSetup())
                settings.restorePortable(PortablePreferences("base", "en", ""))
                assertEquals(done, settings.firstRunSetup.first())
                assertEquals(FirstRunSetup.ExistingInstall, settings.dismissSetupCard())
            }
            settingsSession(context, file) { settings ->
                assertEquals(FirstRunSetup.ExistingInstall, settings.firstRunSetup.first())
                assertEquals(FirstRunSetup.ExistingInstall, settings.initializeFirstRunSetup(pending))
                assertEquals(FirstRunSetup.ExistingInstall, settings.completeFirstRunSetup())
            }
        } finally {
            file.delete()
        }
    }
}
