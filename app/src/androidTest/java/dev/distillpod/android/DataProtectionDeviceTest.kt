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

    @Test fun syncPageOpensRestoreAndBackReturnsToSync() = runBlocking<Unit> {
        val graph = compose.activity.applicationContext.appGraph
        graph.ready.await()
        withContext(Dispatchers.Main) {
            ViewModelProvider(compose.activity, AppViewModel.factory(graph))[AppViewModel::class.java].open("settings:sync")
        }
        assertBindingDisclosure()
        compose.onNodeWithText("Set up automatic backup").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Restore from a backup").performScrollTo().performClick()
        compose.onNodeWithText("Automatic: updated once a day when your library changes, the phone is online and the battery is sufficient.").performScrollTo().assertIsDisplayed()
        // Restore is a sub-page of Sync: there is no separate cross-link back, only Back.
        compose.onNodeWithText("Open Library sync").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Set up automatic backup").performScrollTo().assertIsDisplayed()
    }

    /** The binding disclosure is kept verbatim in the sync page's Learn more sheet (#203); a build without sync has none (#323). */
    private fun assertBindingDisclosure() {
        val disclosure = if (dev.distillpod.android.sync.SyncBuild.ENABLED) "Connecting binds this library" else "use Restore from a backup to bring back your library"
        compose.onNodeWithContentDescription("Learn more about What's covered").performScrollTo().performClick()
        compose.onNodeWithText(disclosure, substring = true).performScrollTo().assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(5000) { compose.onAllNodesWithText(disclosure, substring = true).fetchSemanticsNodes().isEmpty() }
    }
}
