package dev.distillpod.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import dev.distillpod.android.features.*
import dev.distillpod.android.playback.PlaybackState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Deterministic controls and action dispatch; no account, microphone or paid request. */
class VideoOverlayDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun voiceOverlayUsesTheExistingSessionSheetAndReturnsToVideo() {
        var voice by mutableStateOf(dev.distillpod.android.voice.VoiceState())
        var finished = 0
        var cancelled = 0
        compose.activity.setContent { DistillTheme {
            VoicePlayerInteraction(voice, { voice = voice.copy(phase = "Preparing"); true }, { finished++ }, { cancelled++; voice = voice.copy(phase = "Idle") },
                content = { enabled, ask -> VideoOverlay(PlaybackState(playing = true, durationMs = 120_000), "Fixture", false, false, voice.active, enabled,
                    {}, {}, {}, ask, {}) })
        } }
        compose.onNodeWithText("Voice question").performClick()
        compose.onNodeWithText("Getting ready…").assertExists()
        compose.runOnIdle { voice = voice.copy(phase = "Listening") }
        compose.onNodeWithText("Listening…").assertExists()
        compose.waitUntil(5000) { compose.onAllNodes(hasText("Done") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Done").performClick()
        assertEquals(1, finished)
        compose.runOnIdle { voice = voice.copy(phase = "Answering", answer = "Synthetic answer") }
        compose.onNodeWithContentDescription("Spoken answer transcript").assertExists()
        compose.onNodeWithText("Stop and resume").performScrollTo().performClick()
        assertEquals(1, cancelled)
        compose.onNodeWithTag("Fullscreen video").assertExists()
        compose.onNodeWithText("Voice question").assertExists()
    }

    @Test fun controlsHideRevealPauseSeekAndDispatchActions() {
        var state by mutableStateOf(PlaybackState(episodeId = "fixture", positionMs = 20_000, durationMs = 120_000, playing = true))
        var captures = 0
        var asks = 0
        var exits = 0
        var seek = -1L
        compose.mainClock.autoAdvance = false
        compose.activity.setContent { DistillTheme {
            VideoOverlay(state, "Video fixture", false, false, false, true,
                onToggle = { state = state.copy(playing = !state.playing) }, onSeek = { seek = it },
                onCapture = { captures++ }, onVoice = { asks++ }, onExit = { exits++ })
        } }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithContentDescription("Pause video").assertExists()
        compose.mainClock.advanceTimeBy(4000)
        compose.onNodeWithContentDescription("Pause video").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show or hide video controls").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithContentDescription("Pause video").performClick()
        compose.mainClock.advanceTimeBy(5000)
        compose.onNodeWithContentDescription("Play video").assertExists()
        compose.onNodeWithText("Distill", substring = false).performClick()
        compose.onNodeWithText("Voice question").performClick()
        compose.onNodeWithContentDescription("Back 10 seconds").performClick()
        assertEquals(10_000L, seek)
        compose.onNodeWithContentDescription("Forward 30 seconds").performClick()
        assertEquals(50_000L, seek)
        compose.onNodeWithContentDescription("Video playback position").performSemanticsAction(SemanticsActions.SetProgress) { it(60_000f) }
        assertEquals(60_000L, seek)
        compose.onNodeWithContentDescription("Exit fullscreen").performClick()
        assertEquals(1, captures); assertEquals(1, asks); assertEquals(1, exits)
    }
    @Test fun activeVoiceAndCaptureKeepControlsVisibleAndBlockDuplicateActions() {
        var voice by mutableStateOf(true)
        var capture by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.activity.setContent { DistillTheme {
            VideoOverlay(PlaybackState(playing = true, durationMs = 120_000), "Fixture", false, capture, voice, true,
                {}, {}, { error("Must not capture twice") }, { error("Must not start overlapping voice") }, {})
        } }
        compose.mainClock.advanceTimeBy(6000)
        compose.onNodeWithText("Distill", substring = false).assertIsNotEnabled()
        compose.onNodeWithText("Voice active").assertIsNotEnabled()
        compose.runOnIdle { voice = false; capture = true }
        compose.mainClock.advanceTimeBy(6000)
        compose.onNodeWithText("Saving…").assertIsNotEnabled()
        compose.onNodeWithText("Voice question").assertIsNotEnabled()
    }
}
