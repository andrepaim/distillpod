package dev.distillpod.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.distillpod.android.features.SyncCoverageInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A build with sync keeps the complete "What's covered" sheet whether or not it is configured; the isolated
 * emulator runs an unconfigured build (SubscriptionSyncUiDeviceTest, DataProtectionDeviceTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncCoverageSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `an unconfigured build with sync shows every scope and binding disclosure`() {
        compose.setContent { MaterialTheme { SyncCoverageInfo(configured = false, contentConfigured = false, expandedScope = false) } }
        compose.onNodeWithText("Cloud sync is unavailable in this build. Automatic backup still keeps your whole library in a file you choose.").assertExists()
        compose.onNodeWithContentDescription("Learn more about What's covered").performClick()
        compose.waitForIdle()
        for (text in listOf("Neither transfers audio downloads, model binaries or account secrets.",
            "On a new phone, sign in to sync with the same Google account", "Connecting binds this library to its original Google account."))
            compose.onAllNodesWithText(text, substring = true).assertCountEquals(1)
    }
}
