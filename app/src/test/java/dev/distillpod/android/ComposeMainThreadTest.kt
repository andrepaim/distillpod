package dev.distillpod.android

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ComposeMainThreadTest {
    @get:Rule val compose = createQueuedAndroidComposeRule<ComponentActivity>()

    @Test fun backgroundCompletionResumesUiEffectOnAndroidMain() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val resumedOnMain = AtomicReference<Boolean?>()
        compose.setContent {
            LaunchedEffect(Unit) {
                entered.complete(Unit)
                release.await()
                resumedOnMain.set(Looper.myLooper() == Looper.getMainLooper())
            }
            Text("Thread-affinity fixture")
        }
        // Compose 1.7.8's Robolectric strategy skips custom idlers; drive its clock explicitly.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertTrue("UI effect must be suspended before the background completion", entered.isCompleted)
        runBlocking { withContext(Dispatchers.Default) { release.complete(Unit) } }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals("Background completion must resume UI effects on Android main", true, resumedOnMain.get())
    }
}
