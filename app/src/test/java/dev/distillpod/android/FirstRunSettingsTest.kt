package dev.distillpod.android

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.AppSettings
import dev.distillpod.android.data.PortablePreferences
import dev.distillpod.core.FirstRunSetup
import dev.distillpod.core.FirstRunStatus
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FirstRunSettingsTest {
    @Test fun firstRunWriteLetsAnotherSettingBeSavedWhileTheCallerIsPaused() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File(context.cacheDir, "onboarding-dispatcher-test.preferences_pb")
        val timeoutMillis = 10_000L
        val storeJob = SupervisorJob()
        val settings = AppSettings(context,
            PreferenceDataStoreFactory.create(scope = CoroutineScope(storeJob + Dispatchers.IO)) { file })
        val callbacks = Channel<Runnable>(Channel.UNLIMITED)
        val paused = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { callbacks.trySend(block).getOrThrow() }
        }
        val pending = FirstRunSetup(FirstRunStatus.PENDING, cardDismissed = false)
        val callerJob = SupervisorJob()
        val startup = CoroutineScope(callerJob + paused).async(start = CoroutineStart.UNDISPATCHED) {
            settings.initializeFirstRunSetup(pending)
        }
        try {
            // Wait until initialization posts to the paused caller. A write queued behind it must
            // still finish before that dispatch is allowed to run.
            val callback = withTimeout(timeoutMillis) { callbacks.receive() }
            try {
                withTimeout(timeoutMillis) {
                    settings.flag("wifi", false)
                    assertFalse(settings.flow.first().wifiOnly)
                    assertEquals(pending, settings.firstRunSetup.first())
                }
            } finally {
                callback.run()
                withTimeout(timeoutMillis) {
                    while (!startup.isCompleted) select<Unit> {
                        callbacks.onReceive { it.run() }
                        startup.onJoin { }
                    }
                }
            }
            assertEquals(pending, startup.await())
        } finally {
            callerJob.cancel()
            withTimeout(timeoutMillis) { storeJob.cancelAndJoin() }
            file.delete()
        }
    }

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
