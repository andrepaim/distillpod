package dev.distillpod.android

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.distillpod.android.data.DistillationEntity
import dev.distillpod.android.features.PlayerScrubber
import dev.distillpod.android.features.chapterStarts
import dev.distillpod.android.features.momentMarks
import dev.distillpod.android.features.trackFraction
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerScrubberTest {
    @get:Rule val compose = createComposeRule()

    @Test fun keepsThePositionSemanticsAndSeeksOnRelease() {
        val position = mutableLongStateOf(75_000)
        val seeks = mutableListOf<Long>()
        compose.setContent { PlayerScrubber({ position.longValue }, 220_000, { seeks += it }, moments = listOf(5_000), chapters = listOf(60_000)) }
        compose.onNodeWithContentDescription("Episode playback position")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1:15 of 3:40"))
        compose.onNodeWithText("1:15").assertIsDisplayed()
        compose.onNodeWithText("3 min left").assertIsDisplayed()
        compose.runOnIdle { position.longValue = 80_000 }
        compose.onNodeWithText("1:20").assertIsDisplayed()
        compose.onNodeWithContentDescription("Episode playback position")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(120_000f) }
        compose.runOnIdle { assertEquals(listOf(120_000L), seeks) }
    }

    @Test fun anUnknownDurationCannotSeek() {
        compose.setContent { PlayerScrubber({ 0 }, 0, { fail("No seek without a duration") }) }
        compose.onNodeWithContentDescription("Episode playback position").assertIsNotEnabled()
        compose.onNodeWithText("Duration unavailable").assertExists()
    }

    @Test fun markersAndChapterGapsComeFromTheEpisodeData() {
        assertEquals(.5f, trackFraction(50, 100))
        assertNull(trackFraction(150, 100))
        assertNull(trackFraction(-1, 100))
        assertNull(trackFraction(10, 0))
        assertEquals(listOf(60_000L, 120_000L),
            chapterStarts("""[{"startMs":0,"title":"Intro"},{"startMs":120000,"title":"B"},{"startMs":60000,"title":"A"},{"startMs":60000,"title":"A again"}]"""))
        assertEquals(emptyList<Long>(), chapterStarts("not json"))
        val captures = listOf(DistillationEntity("a", "ep", 65_000, 125_000), DistillationEntity("b", "other", 1_000, 61_000),
            DistillationEntity("c", "ep", 5_000, 65_000))
        assertEquals(listOf(5_000L, 65_000L), momentMarks(captures, "ep"))
    }
}
