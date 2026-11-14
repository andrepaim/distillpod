package dev.distillpod.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.distillpod.android.features.AppViewModel
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Navigation only; never connects accounts, writes backups or restores owner data. */
@RunWith(AndroidJUnit4::class)
class DataProtectionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun syncAndBackupCrossLinksPreserveBackNavigation() = runBlocking<Unit> {
        val graph = compose.activity.applicationContext.appGraph
        graph.ready.await()
        withContext(Dispatchers.Main) {
            ViewModelProvider(compose.activity, AppViewModel.factory(graph))[AppViewModel::class.java].open("settings:sync")
        }
        assertBindingDisclosure()
        compose.onNodeWithText("Open Backup & Restore").performScrollTo().performClick()
        compose.onNodeWithText("Manual only: backups do not run on a schedule.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open Library sync").performScrollTo().performClick()
        assertBindingDisclosure()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Manual only: backups do not run on a schedule.").performScrollTo().assertIsDisplayed()
    }

    /** The binding disclosure is kept verbatim in the sync page's Learn more sheet (#203). */
    private fun assertBindingDisclosure() {
        compose.onNodeWithContentDescription("Learn more about Sync and backup").performScrollTo().performClick()
        compose.onNodeWithText("Connecting binds this library", substring = true).performScrollTo().assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Connecting binds this library", substring = true).fetchSemanticsNodes().isEmpty() }
    }
}
