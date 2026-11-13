package dev.distillpod.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.distillpod.android.features.SleepTimerControl
import dev.distillpod.android.features.sleepTimerClock
import dev.distillpod.android.features.sleepTimerSpoken
import dev.distillpod.core.SleepTimerStatus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepTimerControlTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()
    private val status = mutableStateOf<SleepTimerStatus?>(null)

    private fun show(now: Long = 0) = compose.setContent {
        SleepTimerControl(status.value, onStart = { calls += "start:$it" }, onEndOfEpisode = { calls += "end" },
            onExtend = { calls += "extend" }, onCancel = { calls += "cancel" }, clock = { now })
    }

    @Test fun offersEveryDurationAndEndOfEpisode() {
        show()
        compose.onNodeWithText("Sleep timer").assertHasClickAction().performClick()
        listOf("5 minutes", "10 minutes", "15 minutes", "30 minutes", "45 minutes", "60 minutes", "End of episode")
            .forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("15 minutes").performScrollTo().performClick()
        assertEquals(listOf("start:900000"), calls)
        compose.onNodeWithText("End of episode").assertDoesNotExist()
        compose.onNodeWithText("Sleep timer").performClick()
        compose.onNodeWithText("End of episode").performScrollTo().performClick()
        assertEquals(listOf("start:900000", "end"), calls)
    }

    @Test fun dismissingTheChoiceStartsNothing() {
        show()
        compose.onNodeWithText("Sleep timer").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("15 minutes").assertDoesNotExist()
        assertTrue(calls.isEmpty())
    }

    @Test fun runningCountdownShowsRemainingTimeAndCanBeExtendedOrCancelled() {
        status.value = SleepTimerStatus(endOfEpisode = false, remainingMs = 872_000, counting = true, anchorMs = 0)
        show(now = 0)
        compose.onNodeWithText("Sleep in 14:32").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sleep timer: 15 minutes remaining").assertExists()
        compose.onNodeWithContentDescription("Extend sleep timer by 5 minutes").assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("Cancel sleep timer").assertHasClickAction().performClick()
        assertEquals(listOf("extend", "cancel"), calls)
        compose.runOnIdle { status.value = null }
        compose.onNodeWithText("Sleep timer").assertIsDisplayed()
    }

    @Test fun pausedCountdownSaysItIsWaiting() {
        status.value = SleepTimerStatus(endOfEpisode = false, remainingMs = 300_000, counting = false, anchorMs = 0)
        show(now = 120_000)
        compose.onNodeWithText("Sleep in 5:00 · paused").assertIsDisplayed()
    }

    @Test fun endOfEpisodeCannotBeExtended() {
        status.value = SleepTimerStatus(endOfEpisode = true, remainingMs = 0, counting = false, anchorMs = 0)
        show()
        compose.onNodeWithText("Sleep at end of episode").assertIsDisplayed()
        compose.onNodeWithContentDescription("Extend sleep timer by 5 minutes").assertDoesNotExist()
        compose.onNodeWithContentDescription("Cancel sleep timer").performClick()
        assertEquals(listOf("cancel"), calls)
    }

    @Test fun remainingTimeFormatting() {
        assertEquals("14:32", sleepTimerClock(872_000))
        assertEquals("14:32", sleepTimerClock(871_001))
        assertEquals("1:00:00", sleepTimerClock(3_600_000))
        assertEquals("0:01", sleepTimerClock(1))
        assertEquals("0:00", sleepTimerClock(0))
        assertEquals("15 minutes remaining", sleepTimerSpoken(872_000))
        assertEquals("1 minute remaining", sleepTimerSpoken(30_000))
        assertEquals("1 minute remaining", sleepTimerSpoken(60_000))
        assertEquals("60 minutes remaining", sleepTimerSpoken(3_600_000))
    }
}
