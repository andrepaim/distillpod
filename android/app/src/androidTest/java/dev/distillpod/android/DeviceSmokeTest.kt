package dev.distillpod.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.distillpod.core.parseFeed
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<DiagnosticsActivity>()

    @Test fun launchAndMissingFilesRecoverWithoutCrash() {
        val context = compose.activity
        context.getFileStreamPath("whisper-model.bin").delete()
        context.getFileStreamPath("clip.wav").delete()
        compose.onNodeWithText("DistillPod").assertIsDisplayed()
        val hasConnection = compose.onAllNodesWithText("Disconnect account").fetchSemanticsNodes().isNotEmpty()
        val ai = compose.onNodeWithText("Run AI check").performScrollTo()
        if(hasConnection) ai.assertIsEnabled() else ai.assertIsNotEnabled()
        compose.onNodeWithText("Transcribe clip").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Import a Whisper model and an audio clip first").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Transcribe clip").assertIsEnabled()
    }

    @Test fun credentialsSurviveVaultRecreationAndDisconnect() {
        val vault = TokenVault(compose.activity, "instrumentation-vault", "distillpod-test-credentials")
        try {
            vault.write("test-token-envelope")
            assertEquals("test-token-envelope", TokenVault(compose.activity, "instrumentation-vault", "distillpod-test-credentials").read())
            vault.write(null)
            assertNull(TokenVault(compose.activity, "instrumentation-vault", "distillpod-test-credentials").read())
        } finally { vault.write(null) }
    }

    @Test fun credentialVaultNamespacesAreIsolated() {
        val first = TokenVault(compose.activity, "test-vault-first", "test-key-first")
        val second = TokenVault(compose.activity, "test-vault-second", "test-key-second")
        try {
            first.write("first-test-value")
            second.write("second-test-value")
            assertEquals("first-test-value", first.read())
            assertEquals("second-test-value", second.read())
        } finally { first.write(null); second.write(null) }
    }

    @Test fun androidXmlParserReadsFeedAndRejectsExternalEntities() {
        val feed = parseFeed("https://example.test/rss", """<rss><channel><title>Show</title><item><guid>one</guid><title>Episode</title><enclosure url="https://example.test/one.mp3"/></item></channel></rss>""")
        assertEquals("Episode", feed.episodes.single().title)
        assertThrows(Exception::class.java) {
            parseFeed("feed", """<!DOCTYPE rss [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss><channel><title>&x;</title></channel></rss>""")
        }
    }

    @Test fun activityRecreationRemainsUsable() {
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("DistillPod").assertIsDisplayed()
        compose.onNodeWithText("Import audio clip").performScrollTo().assertIsEnabled()
    }
}
