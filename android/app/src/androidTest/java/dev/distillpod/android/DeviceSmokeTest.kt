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
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun launchAndMissingFilesRecoverWithoutCrash() {
        val context = compose.activity
        context.getFileStreamPath("whisper-model.bin").delete()
        context.getFileStreamPath("clip.wav").delete()
        compose.onNodeWithText("DistillPod").assertIsDisplayed()
        compose.onNodeWithText("Run AI check").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Transcribe clip").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Import a Whisper model and a 16 kHz mono PCM16 WAV clip first").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Transcribe clip").assertIsEnabled()
    }

    @Test fun credentialsSurviveVaultRecreationAndDisconnect() {
        val vault = TokenVault(compose.activity)
        try {
            vault.write("test-token-envelope")
            assertEquals("test-token-envelope", TokenVault(compose.activity).read())
            vault.write(null)
            assertNull(TokenVault(compose.activity).read())
        } finally { vault.write(null) }
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
        compose.onNodeWithText("Import WAV clip").performScrollTo().assertIsEnabled()
    }
}
