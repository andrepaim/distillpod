package dev.distillpod.android

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.WorkManager
import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.data.ManagedBackups
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.android.features.AppViewModel
import dev.distillpod.android.features.FirstRunTestAccess
import dev.distillpod.android.features.OnboardingRecoveryRoute
import dev.distillpod.android.features.OnboardingWelcomeRoute
import dev.distillpod.android.work.UpdateNotice
import dev.distillpod.core.FirstRunSetup
import dev.distillpod.core.FirstRunStatus
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run a single case at a time with firstRunOnboarding=true and clean app data on a disposable emulator. */
@RunWith(AndroidJUnit4::class)
class OnboardingNavigationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val welcome = listOf(OnboardingWelcomeRoute)
    private val recovery = welcome + OnboardingRecoveryRoute
    private val upgradeEpisode = "onboarding-upgrade-episode"
    private val upgradeProgress = 29_000L

    @Suppress("DEPRECATION")
    private fun uninitializedApp(updated: Boolean = false): Context {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("firstRunOnboarding") == "true")
        assumeTrue((args.getString("firstRunUpgrade") == "true") == updated)
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic")) { "Use a disposable emulator" }
        val context = instrumentation.targetContext
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        assertTrue(installed.firstInstallTime > 0 && installed.lastUpdateTime >= installed.firstInstallTime)
        assertEquals("Expected fresh install or explicit upgrade", !updated, installed.firstInstallTime == installed.lastUpdateTime)
        assertNull(runBlocking { context.appGraph.settings.firstRunSetup.first() })
        return context
    }

    private fun awaitStack(scenario: ActivityScenario<MainActivity>, expected: List<String>): AppViewModel {
        lateinit var vm: AppViewModel
        scenario.onActivity { activity ->
            vm = ViewModelProvider(activity, AppViewModel.factory(activity.applicationContext.appGraph))[AppViewModel::class.java]
        }
        try {
            compose.waitUntil(15_000) { vm.loaded.value && vm.backStack.value == expected }
            // A ViewModel route may be set before Compose has installed its Back callback.
            compose.waitForIdle()
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Expected stack $expected, actual ${vm.backStack.value}, loaded=${vm.loaded.value}, " +
                "setup=${vm.onboarding.value}, activity=${scenario.state}, foreground=${device.currentPackageName}", timeout)
        }
        return vm
    }

    private fun tap(label: String) = compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()

    private fun assertComplete(context: Context) {
        assertEquals(FirstRunSetup(FirstRunStatus.DONE, cardDismissed = false), runBlocking { context.appGraph.settings.firstRunSetup.first() })
        assertFalse(runBlocking { context.appGraph.managedBackups.connected.first() })
    }

    @Test fun externalDestinationsAndRecoverySurviveActivityRecreation() {
        val context = uninitializedApp()
        val request = Intent(context, MainActivity::class.java)
            .putExtra("openPlayer", true)
            .putExtra("query", "A note from the caller")
        ActivityScenario.launch<MainActivity>(request).use { scenario ->
            val vm = awaitStack(scenario, welcome + "player")
            assertEquals("Only the requested destination is applied", "", vm.query.value)
            scenario.onActivity { activity ->
                activity.startActivity(Intent(activity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(UpdateNotice.OPEN_ABOUT, true))
            }
            val about = welcome + listOf("player", "settings:about")
            awaitStack(scenario, about)
            scenario.recreate()
            awaitStack(scenario, about)
            device.pressBack()
            awaitStack(scenario, welcome + "player")
            device.pressBack()
            awaitStack(scenario, welcome)
            scenario.recreate()
            awaitStack(scenario, welcome)
            compose.onNodeWithText("Library").assertDoesNotExist()

            tap("Connect ChatGPT")
            awaitStack(scenario, welcome + "settings:ai")
            device.pressBack()
            awaitStack(scenario, welcome)
            tap("Bring back my library")
            awaitStack(scenario, recovery)
            scenario.recreate()
            awaitStack(scenario, recovery)
            tap("Sync & backups")
            awaitStack(scenario, recovery + "settings:sync")
            tap("Restore from a backup")
            val backups = recovery + listOf("settings:sync", "backup")
            awaitStack(scenario, backups)
            scenario.recreate()
            awaitStack(scenario, backups)
            device.pressBack()
            awaitStack(scenario, recovery + "settings:sync")
            device.pressBack()
            awaitStack(scenario, recovery)
            device.pressBack()
            awaitStack(scenario, welcome)
            assertEquals(FirstRunStatus.PENDING, runBlocking { context.appGraph.settings.firstRunSetup.first()?.status })
            tap("Find my first podcast")
            awaitStack(scenario, listOf("search"))
            assertComplete(context)
        }
        ActivityScenario.launch(MainActivity::class.java).use { awaitStack(it, listOf("library")) }
    }

    @Test fun welcomeSkipCompletesSetup() = leaveSetup("Skip setup", restore = false)

    @Test fun recoverySkipCompletesSetup() = leaveSetup("Skip setup", restore = true)

    @Test fun recoveryOpenLibraryCompletesSetup() = leaveSetup("Open library", restore = true)

    @Test fun mediaSearchPlaysAbovePendingSetup() {
        val context = uninitializedApp()
        val graph = context.appGraph
        val library = graph.db.library()
        val episode = "onboarding-media-search"
        val title = "Media search fixture"
        val audio = File.createTempFile("onboarding-media-search", ".wav", context.cacheDir)
        try {
            instrumentation.context.assets.open("jfk.wav").use { input -> audio.outputStream().use { input.copyTo(it) } }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitStack(scenario, welcome)
                runBlocking {
                    library.putPodcast(PodcastEntity(episode, "https://example.test/onboarding.rss", title))
                    library.putEpisode(EpisodeEntity(episode, episode, "guid", title,
                        "https://example.test/onboarding.wav", localFile = audio.path, downloadState = "downloaded"))
                }
                scenario.onActivity { activity ->
                    activity.startActivity(Intent(activity, MainActivity::class.java)
                        .setAction("android.media.action.MEDIA_PLAY_FROM_SEARCH")
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(SearchManager.QUERY, title))
                }
                awaitStack(scenario, welcome + "player")
                compose.waitUntil(10_000) { graph.playback.state.value.episodeId == episode }
                device.pressBack()
                awaitStack(scenario, welcome)
                assertEquals(FirstRunStatus.PENDING, runBlocking { graph.settings.firstRunSetup.first()?.status })
            }
        } finally {
            runBlocking {
                graph.playback.clear()
                library.deletePodcast(episode)
            }
            audio.delete()
        }
    }

    private fun leaveSetup(label: String, restore: Boolean) {
        val context = uninitializedApp()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitStack(scenario, welcome)
            if (restore) {
                tap("Bring back my library")
                awaitStack(scenario, recovery)
            }
            tap(label)
            awaitStack(scenario, listOf("library"))
            assertComplete(context)
        }
    }

    @Test fun seedLibraryBeforeUpgrade() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("firstRunUpgradeSeed") == "true")
        val context = uninitializedApp()
        val graph = context.appGraph
        // Seed a previous installation without recording a new setup decision.
        FirstRunTestAccess.bypass = true
        try {
            runBlocking {
                graph.ready.await()
                graph.db.library().putPodcast(PodcastEntity("onboarding-upgrade-show", "https://example.test/upgrade.rss", "Upgrade fixture"))
                graph.db.library().putEpisode(EpisodeEntity(upgradeEpisode, "onboarding-upgrade-show", "guid", "Upgrade episode",
                    "https://example.test/upgrade.wav", positionMs = upgradeProgress))
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitStack(scenario, listOf("library"))
                scenario.onActivity { activity ->
                    ViewModelProvider(activity, AppViewModel.factory(graph))[AppViewModel::class.java].open("backup")
                }
                awaitStack(scenario, listOf("library", "backup"))
                tap("Create backup file")
                DocumentPickerUi.location(device, By.text("DistillPod test files"), synchronizeApp = compose::waitForIdle)
                checkNotNull(device.wait(Until.findObject(By.text(Pattern.compile("Save", Pattern.CASE_INSENSITIVE)).enabled(true)), 10_000)).click()
                compose.waitUntil(20_000) { graph.managedBackups.state.value.let { it.error || (!it.working && it.history.isNotEmpty()) } }
                assertFalse(graph.managedBackups.state.value.message, graph.managedBackups.state.value.error)
                val location = checkNotNull(graph.managedBackups.state.value.location)
                compose.waitUntil(15_000) { scheduledBackupId(context) != null }
                File(context.filesDir, "onboarding-upgrade-backup").writeText("${location.uri}\n${checkNotNull(scheduledBackupId(context))}")
                assertNull(runBlocking { graph.settings.firstRunSetup.first() })
            }
        } finally {
            FirstRunTestAccess.bypass = false
        }
    }

    @Test fun updatedInstallOpensItsLibraryWithoutTheNewSetup() {
        val context = uninitializedApp(updated = true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitStack(scenario, listOf("library"))
            assertEquals(FirstRunSetup.ExistingInstall, runBlocking { context.appGraph.settings.firstRunSetup.first() })
            compose.onNodeWithText("Skip setup").assertDoesNotExist()
            compose.onNodeWithText("Dismiss").assertDoesNotExist()
            if (InstrumentationRegistry.getArguments().getString("firstRunUpgradeSeed") == "true") {
                val graph = context.appGraph
                val location = runBlocking {
                    graph.ready.await()
                    assertEquals(upgradeProgress, graph.db.library().episode(upgradeEpisode)?.positionMs)
                    checkNotNull(ManagedBackups.Location.parse(graph.settings.value("managedBackup").first()))
                }
                assertTrue(context.contentResolver.persistedUriPermissions.any {
                    it.uri == location.uri && it.isReadPermission && it.isWritePermission
                })
                val (expectedUri, expectedWorkId) = File(context.filesDir, "onboarding-upgrade-backup").readLines()
                assertEquals(expectedUri, location.uri.toString())
                assertEquals(expectedWorkId, scheduledBackupId(context))
            }
        }
    }

    private fun scheduledBackupId(context: Context): String? = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork("managed-backup").get(5, TimeUnit.SECONDS)
        .singleOrNull { !it.state.isFinished }?.id?.toString()
}
