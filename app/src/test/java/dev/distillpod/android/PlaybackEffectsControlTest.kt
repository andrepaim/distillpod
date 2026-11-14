package dev.distillpod.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.width
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.distillpod.android.features.PlaybackEffectsControl
import dev.distillpod.android.features.PlaybackEffectsState
import dev.distillpod.android.features.SPEED_SLIDER_STEPS
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
        compose.onNodeWithText("Skip silence").assertIsOff().performClick()
        compose.onNodeWithText("Volume boost").assertIsOff().performClick()
        assertEquals(listOf("silence:true", "boost:true"), calls)
        state.value = state.value.copy(skipSilence = true, volumeBoost = true)
        compose.onNodeWithText("Skip silence").assertIsOn().performClick()
        compose.onNodeWithText("Volume boost").assertIsOn()
        assertEquals("silence:false", calls.last())
        compose.onNodeWithText("Volume boost isn't available on this device", substring = true).assertDoesNotExist()
    }

    @Test fun anUnavailableBoostIsExplained() {
        state.value = PlaybackEffectsState(speed = 1f, volumeBoost = true, boostUnavailable = true)
        show()
        compose.onNodeWithText("Volume boost isn't available on this device", substring = true).assertExists()
    }

    /** Player screens find their own switches by sibling text; this control must not join their sibling set. */
    @Test fun itsSwitchesAreNotSiblingsOfNeighbouringPlayerControls() {
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                androidx.compose.foundation.layout.Row {
                    androidx.compose.material3.Text("Skip marked ads")
                    androidx.compose.material3.Switch(checked = false, onCheckedChange = {})
                }
                PlaybackEffectsControl(state.value, onSpeed = {}, onKeepForShow = {}, onSkipSilence = {}, onVolumeBoost = {})
            }
        }
        compose.onAllNodes(isToggleable() and hasAnySibling(hasText("Skip marked ads"))).assertCountEquals(1)
    }

    /** In the sheet on a small phone the presets share one line, below the current value and its steps. */
    @Test fun presetsShareOneLineBelowTheCurrentValueOnANarrowSheet() {
        compose.setContent {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(androidx.compose.ui.unit.Dp(320f))) {
                PlaybackEffectsControl(state.value, onSpeed = {}, onKeepForShow = {}, onSkipSilence = {}, onVolumeBoost = {})
            }
        }
        val first = compose.onNodeWithText("1×").getUnclippedBoundsInRoot().top
        listOf("1.25×", "1.5×", "2×").forEach {
            assertEquals("$it is on the first line", first, compose.onNodeWithText(it).getUnclippedBoundsInRoot().top)
        }
        assertTrue("The current value comes first",
            compose.onNodeWithContentDescription("Playback speed 1.25×").getUnclippedBoundsInRoot().bottom <= first)
    }

    /** The slider covers 0.5× to 3× in 0.05× steps and applies the speed when released. */
    @Test fun theSliderSetsSpeedsInTwentiethSteps() {
        assertEquals(49, SPEED_SLIDER_STEPS)
        show()
        compose.onNodeWithContentDescription("Adjust playback speed").assertExists()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(1.75f) }
        compose.waitForIdle()
        assertEquals(listOf("speed:1.75"), calls)
    }
}
