package dev.distillpod.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.distillpod.android.data.TranscriptEntity
import dev.distillpod.android.features.*
import dev.distillpod.android.ui.theme.DistillTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The episode page's ways forward for a waiting transcript (#225) and the replacement confirmation (#229). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptChoicesUiTest {
    @get:Rule val compose = createComposeRule()

    private fun waiting(model: String, savedMs: Long = 0) =
        TranscriptEntity("e", "r", "audio", model, "auto", state = "waiting_model", processedMs = savedMs, durationMs = 600_000)

    @Test fun `a waiting transcript without saved chunks can be prepared with the selected ready model`() {
        assertEquals(WaitingModelChoice(prepareWith = "base"), waitingModelChoice(waiting("tiny"), "base", setOf("base")))
        val state = TranscriptToolState(waiting("tiny"), null, false, 0, 0, 0, 0f, null, "", "", null, false,
            waitingModelChoice(waiting("tiny"), "base", setOf("base")))
        assertEquals("Prepare with Base", state.prepareLabel)
        assertFalse(state.needsModel)
    }

    @Test fun `a waiting transcript resumes with its own model once that model is ready`() {
        assertEquals(WaitingModelChoice(prepareWith = "tiny"), waitingModelChoice(waiting("tiny", 300_000), "base", setOf("tiny", "base")))
        val state = TranscriptToolState(waiting("tiny", 300_000), null, false, 0, 0, 0, 0f, null, "", "", null, false,
            waitingModelChoice(waiting("tiny", 300_000), "base", setOf("tiny")))
        assertEquals("Resume transcript", state.prepareLabel)
    }

    @Test fun `saved chunks offer their own model or an explicit start over`() {
        assertEquals(WaitingModelChoice(download = "tiny", startOverWith = "base"), waitingModelChoice(waiting("tiny", 300_000), "base", setOf("base")))
        assertEquals(WaitingModelChoice(download = "tiny"), waitingModelChoice(waiting("tiny", 300_000), "base", emptySet()))
        assertEquals(WaitingModelChoice(download = "tiny"), waitingModelChoice(waiting("tiny", 300_000), "tiny", emptySet()))
        val state = TranscriptToolState(waiting("tiny", 300_000), null, false, 0, 0, 0, 0f, null, "", "", null, false,
            waitingModelChoice(waiting("tiny", 300_000), "base", setOf("base")))
        assertTrue(state.needsModel)
        assertNull(state.prepareLabel)
    }

    @Test fun `without any ready model the transcript still asks for a download`() {
        assertEquals(WaitingModelChoice(), waitingModelChoice(waiting("tiny"), "tiny", emptySet()))
        assertNull(waitingModelChoice(waiting("tiny").copy(state = "paused"), "tiny", emptySet()))
    }

    @Test fun `the confirmations explain what is replaced and what stays readable`() {
        val again = transcribeAgainConfirmation("base", "en", modelReady = false)
        assertEquals("Transcribe locally again?", again.title)
        assertTrue(again.body, again.body.contains("Base and English replaces the current one"))
        assertTrue(again.body, again.body.contains("stays readable and searchable until the new one is ready"))
        assertTrue(again.body, again.body.contains("Base isn't downloaded yet"))
        assertFalse(transcribeAgainConfirmation("base", "auto", modelReady = true).body.contains("downloaded"))
        val restart = startOverConfirmation(waiting("tiny", 300_000), "base")
        assertEquals("Start over with Base?", restart.title)
        assertTrue(restart.body, restart.body.contains("5:00 of this episode was transcribed with Tiny"))
        assertEquals("Start over", restart.confirm)
    }

    @Test fun `only confirming the dialog replaces the transcript`() {
        var confirmed = 0
        var dismissed = 0
        compose.setContent {
            DistillTheme { ReplaceTranscriptDialog(transcribeAgainConfirmation("tiny", "auto", true), { confirmed++ }, { dismissed++ }) }
        }
        compose.onNodeWithText("Transcribe locally again?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, confirmed)
        assertEquals(1, dismissed)
        compose.onNodeWithText("Transcribe again").performClick()
        assertEquals(1, confirmed)
    }
}
