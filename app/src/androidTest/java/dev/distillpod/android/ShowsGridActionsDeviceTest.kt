package dev.distillpod.android

import android.os.Build
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Public Compose semantics with explicit Android-main ownership; emulator only. */
@RunWith(AndroidJUnit4::class)
class ShowsGridActionsDeviceTest {
    @get:Rule val compose = createQueuedAndroidComposeRule<ComponentActivity>()
    private val actions = ShowsGridActions(compose) { "route=shows fixture=grid-actions" }
    private val selected = mutableStateOf(-1)
    private val calls = AtomicInteger()
    private val delta = AtomicReference(0f)

    @Before fun requireEmulator() {
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic"))
    }

    @Test fun keyedLookupAndScrollRunOnAndroidMain() {
        surface()
        actions.scrollToKey("target")
        compose.onNodeWithText("Selected 7").assertIsDisplayed()
        assertEquals(1, calls.get())
    }

    @Test fun indexAndPageScrollRetainTheirPublicActions() {
        surface()
        actions.scrollToIndex(3)
        compose.onNodeWithText("Selected 3").assertIsDisplayed()
        actions.scrollHalfPage()
        assertEquals(2, calls.get())
        assertTrue(delta.get() > 0f)
    }

    @Test fun missingGridFailsWithBoundedContext() {
        surface(0)
        val failure = runCatching { actions.scrollToKey("target") }.exceptionOrNull()
        assertTrue(failure is AssertionError)
        assertTrue(failure!!.message.orEmpty().contains("gridCount=0"))
        assertTrue(failure.message.orEmpty().contains("route=shows"))
        assertEquals(0, calls.get())
    }

    @Test fun duplicateGridFailsWithItsActualCardinality() {
        surface(2)
        val failure = runCatching { actions.scrollToKey("target") }.exceptionOrNull()
        assertTrue(failure is AssertionError)
        assertTrue(failure!!.message.orEmpty().contains("gridCount=2"))
        assertEquals(0, calls.get())
    }

    @Test fun unavailableKeyCannotScrollAnotherItem() {
        surface()
        val failure = runCatching { actions.scrollToKey("absent") }.exceptionOrNull()
        assertTrue(failure is AssertionError)
        assertTrue(failure!!.message.orEmpty().contains("key unavailable"))
        assertEquals(0, calls.get())
    }

    private fun surface(count: Int = 1) {
        compose.setContent {
            Column {
                repeat(count) {
                    Box(Modifier.size(160.dp).testTag("shows-grid").semantics {
                        this[SemanticsProperties.IndexForKey] = { key ->
                            check(Looper.myLooper() == Looper.getMainLooper()) { "Key lookup must run on Android main" }
                            if (key == "target") 7 else -1
                        }
                        this[SemanticsActions.ScrollToIndex] = AccessibilityAction(null, { index ->
                            check(Looper.myLooper() == Looper.getMainLooper())
                            calls.incrementAndGet()
                            selected.value = index
                            true
                        })
                        this[SemanticsActions.ScrollBy] = AccessibilityAction(null, { _, y ->
                            check(Looper.myLooper() == Looper.getMainLooper())
                            calls.incrementAndGet()
                            delta.set(y)
                            true
                        })
                    }) { Text("Selected ${selected.value}") }
                }
            }
        }
    }
}
