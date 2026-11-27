package dev.distillpod.android

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.android.data.AvailableUpdate
import dev.distillpod.android.data.UPDATE_CHECK_WORK
import dev.distillpod.android.data.UpdateState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The Play preset builds without the GitHub update check (#320). These tests run only in that build
 * (src/testUpdateCheckOff): no request, no schedule, no setting and no notice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateCheckOffTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val manager get() = WorkManager.getInstance(context)

    /** Stands in for the worker class an earlier GitHub build scheduled; this build has none. */
    class EarlierBuildWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork() = Result.success()
    }

    @Before fun work() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }
    @After fun close() = WorkManagerTestInitHelper.closeWorkDatabase()

    private fun scheduled() = manager.getWorkInfosForUniqueWork(UPDATE_CHECK_WORK).get(5, TimeUnit.SECONDS)

    @Test fun `the build contains no release check, worker, notice or setting`() {
        assertFalse(UpdateCheckBuild.ENABLED)
        for (name in listOf("dev.distillpod.android.data.ReleaseFeed", "dev.distillpod.android.data.UpdateChecker",
            "dev.distillpod.android.work.UpdateCheckWorker", "dev.distillpod.android.work.UpdateJobs",
            "dev.distillpod.android.work.UpdateNotice", "dev.distillpod.android.features.AppUpdatesUiKt")) {
            assertThrows(name, ClassNotFoundException::class.java) { Class.forName(name) }
        }
    }

    @Test fun `startup schedules no check, whatever the stored setting says`() {
        for (enabled in listOf(true, false)) UpdateCheckBuild.configure(context, enabled)
        assertTrue(scheduled().isEmpty())
    }

    @Test fun `a daily check left by an earlier GitHub build is cancelled`() {
        manager.enqueueUniquePeriodicWork(UPDATE_CHECK_WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<EarlierBuildWorker>(1, TimeUnit.DAYS).build()).result.get(5, TimeUnit.SECONDS)
        UpdateCheckBuild.configure(context, true)
        assertEquals(listOf(WorkInfo.State.CANCELLED), scheduled().map { it.state })
    }

    @Test fun `About shows no update setting, card or check`() {
        val update = AvailableUpdate("9.0.0", 900, "https://github.com/andrepaim/distillpod/releases/tag/v9.0.0",
            "https://github.com/andrepaim/distillpod/releases/download/v9.0.0/distillpod-9.0.0.apk", "Newer.")
        compose.setContent { MaterialTheme { UpdateCheckBuild.AboutSection(UpdateState(available = update), false, {}, {}) } }
        for (text in listOf("Check for updates", "Check now", "Download APK", "DistillPod 9.0.0 is available"))
            compose.onAllNodesWithText(text).assertCountEquals(0)
    }
}
