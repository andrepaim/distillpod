package dev.distillpod.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.distillpod.android.features.SleepTimerControl
import dev.distillpod.android.features.sleepTimerClock
import dev.distillpod.android.features.sleepTimerSpoken
import dev.distillpod.android.features.sleepTimerMinutes
import androidx.compose.ui.semantics.SemanticsActions
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

    /** Robolectric does not route injected touches into the sheet's window; click as TalkBack does. */
    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    @Test fun offersEveryDurationAndEndOfEpisode() {
        show()
        compose.onNodeWithContentDescription("Sleep timer").assertHasClickAction().performClick()
        listOf("5 min", "10 min", "15 min", "30 min", "45 min", "60 min", "End of episode")
            .forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("15 min").performScrollTo().tap()
        assertEquals(listOf("start:900000"), calls)
        compose.onNodeWithText("End of episode").assertDoesNotExist()
        compose.onNodeWithContentDescription("Sleep timer").performClick()
        compose.onNodeWithText("End of episode").performScrollTo().tap()
        assertEquals(listOf("start:900000", "end"), calls)
    }

    @Test fun dismissingTheChoiceStartsNothing() {
        show()
        compose.onNodeWithContentDescription("Sleep timer").performClick()
        compose.onNodeWithText("15 min").assertExists()
        // The sheet's own dismiss action, as TalkBack or a swipe down uses it.
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).performSemanticsAction(SemanticsActions.Dismiss)
        compose.onNodeWithText("15 min").assertDoesNotExist()
        assertTrue(calls.isEmpty())
    }

    @Test fun runningCountdownShowsRemainingTimeAndCanBeExtendedOrCancelled() {
        status.value = SleepTimerStatus(endOfEpisode = false, remainingMs = 872_000, counting = true, anchorMs = 0)
        show(now = 0)
        // The moon carries the minutes left; its label says the same in words.
        compose.onNodeWithContentDescription("Sleep timer on: 15 minutes remaining").assertIsDisplayed()
            .performClick()
        compose.onNodeWithText("Sleep in 14:32").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sleep timer: 15 minutes remaining").assertExists()
        compose.onNodeWithContentDescription("Extend sleep timer by 5 minutes").assertHasClickAction().tap()
        compose.onNodeWithContentDescription("Cancel sleep timer").assertHasClickAction().tap()
        assertEquals(listOf("extend", "cancel"), calls)
        compose.onNodeWithText("Sleep in 14:32").assertDoesNotExist()
        compose.runOnIdle { status.value = null }
        compose.onNodeWithContentDescription("Sleep timer").assertIsDisplayed()
    }

    @Test fun longPressOnTheMoonCancelsARunningTimer() {
        status.value = SleepTimerStatus(endOfEpisode = false, remainingMs = 300_000, counting = true, anchorMs = 0)
        show()
        compose.onNodeWithContentDescription("Sleep timer on: 5 minutes remaining")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .performTouchInput { longClick() }
        assertEquals(listOf("cancel"), calls)
        compose.runOnIdle { status.value = null }
        compose.onNodeWithContentDescription("Sleep timer")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
    }

    @Test fun pausedCountdownSaysItIsWaiting() {
        status.value = SleepTimerStatus(endOfEpisode = false, remainingMs = 300_000, counting = false, anchorMs = 0)
        show(now = 120_000)
        compose.onNodeWithContentDescription("Sleep timer on: 5 minutes remaining, paused").performClick()
        compose.onNodeWithText("Sleep in 5:00 · paused").assertIsDisplayed()
    }

    @Test fun endOfEpisodeCannotBeExtended() {
        status.value = SleepTimerStatus(endOfEpisode = true, remainingMs = 0, counting = false, anchorMs = 0)
        show()
        compose.onNodeWithContentDescription("Sleep timer on: pauses at the end of this episode").performClick()
        compose.onNodeWithText("Sleep at end of episode").assertIsDisplayed()
        compose.onNodeWithContentDescription("Extend sleep timer by 5 minutes").assertDoesNotExist()
        compose.onNodeWithContentDescription("Cancel sleep timer").tap()
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
        assertEquals(15, sleepTimerMinutes(872_000))
        assertEquals(1, sleepTimerMinutes(0))
    }
}
