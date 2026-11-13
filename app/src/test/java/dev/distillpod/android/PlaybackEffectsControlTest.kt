package dev.distillpod.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.distillpod.android.features.PlaybackEffectsControl
import dev.distillpod.android.features.PlaybackEffectsState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackEffectsControlTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()
    private val state = mutableStateOf(PlaybackEffectsState(speed = 1.25f, showTitle = "Synthetic Show"))

    private fun show() = compose.setContent {
        PlaybackEffectsControl(state.value, onSpeed = { calls += "speed:$it" }, onKeepForShow = { calls += "keep:$it" },
            onSkipSilence = { calls += "silence:$it" }, onVolumeBoost = { calls += "boost:$it" })
    }

    @Test fun stepsByATenthFromTheCurrentSpeed() {
        show()
        compose.onNodeWithContentDescription("Playback speed 1.25×").assertExists()
        compose.onNodeWithContentDescription("Faster").performClick()
        compose.onNodeWithContentDescription("Slower").performClick()
        assertEquals(listOf("speed:1.3", "speed:1.2"), calls)
    }

    @Test fun stepsStopAtTheSupportedBounds() {
        state.value = PlaybackEffectsState(speed = 3f)
        show()
        compose.onNodeWithContentDescription("Faster").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Slower").assertIsEnabled()
        state.value = PlaybackEffectsState(speed = 0.5f)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Slower").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Faster").assertIsEnabled()
    }

    @Test fun presetsRemainAndShowTheSelection() {
        show()
        compose.onNodeWithText("1.25×").assertIsSelected()
        compose.onNodeWithText("2×").assertIsNotSelected().performClick()
        assertEquals(listOf("speed:2.0"), calls)
    }

    @Test fun keepingASpeedForTheShow() {
        show()
        compose.onNodeWithText("Keep this speed for Synthetic Show").assertIsOff().performClick()
        assertEquals(listOf("keep:true"), calls)
        state.value = state.value.copy(keepsShowSpeed = true)
        compose.onNodeWithText("Keep this speed for Synthetic Show").assertIsOn().performClick()
        assertEquals(listOf("keep:true", "keep:false"), calls)
    }

    @Test fun noShowMeansNoPerShowChoice() {
        state.value = PlaybackEffectsState(speed = 1f, showTitle = null)
        show()
        compose.onNodeWithText("Keep this speed", substring = true).assertDoesNotExist()
    }

    @Test fun skipSilenceAndVolumeBoostToggle() {
        show()
        compose.onNodeWithText("Skip silence").assertIsNotSelected().performClick()
        compose.onNodeWithText("Volume boost").assertIsNotSelected().performClick()
        assertEquals(listOf("silence:true", "boost:true"), calls)
        state.value = state.value.copy(skipSilence = true, volumeBoost = true)
        compose.onNodeWithText("Skip silence").assertIsSelected().performClick()
        compose.onNodeWithText("Volume boost").assertIsSelected()
        assertEquals("silence:false", calls.last())
        compose.onNodeWithText("Volume boost isn't available on this device", substring = true).assertDoesNotExist()
    }

    @Test fun anUnavailableBoostIsExplained() {
        state.value = PlaybackEffectsState(speed = 1f, volumeBoost = true, boostUnavailable = true)
        show()
        compose.onNodeWithText("Volume boost isn't available on this device", substring = true).assertExists()
    }
}
