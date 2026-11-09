package dev.distillpod.android

import android.os.Build
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android idling integration; Robolectric does not poll custom idling resources. */
@RunWith(AndroidJUnit4::class)
class ComposeMainThreadDeviceTest {
    @get:Rule val compose = createQueuedAndroidComposeRule<ComponentActivity>()

    private fun requireEmulator() {
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic"))
    }

    @Test fun backgroundCompletionResumesUiEffectOnAndroidMain() {
        requireEmulator()
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
        compose.waitForIdle()
        assertTrue("The effect must be suspended before background completion", entered.isCompleted)
        runBlocking { withContext(Dispatchers.Default) { release.complete(Unit) } }
        compose.waitForIdle()
        assertEquals("Background completion must resume UI effects on Android main", true, resumedOnMain.get())
    }

    @Test fun backgroundStateCanShowAndDismissAnAndroidDialog() {
        requireEmulator()
        val show = MutableStateFlow(false)
        compose.setContent {
            val visible by show.collectAsState()
            MaterialTheme {
                Text("Ready for background result")
                if (visible) AlertDialog(onDismissRequest = { show.value = false },
                    title = { Text("Background result") },
                    confirmButton = { TextButton(onClick = { show.value = false }) { Text("Close fixture") } })
            }
        }
        compose.waitForIdle()
        assertTrue("The state collector must start before emission", show.subscriptionCount.value > 0)
        runBlocking { withContext(Dispatchers.Default) { show.value = true } }
        compose.onNodeWithText("Background result").assertIsDisplayed()
        compose.onNodeWithText("Close fixture").performClick()
        compose.onNodeWithText("Background result").assertDoesNotExist()
        assertEquals(false, show.value)
    }

    @Test fun externalPickerWaitDrainsBackgroundCompletionAfterFirstIdle() {
        requireEmulator()
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val entered=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        val effect=AtomicReference<Job?>()
        val released=AtomicBoolean()
        val launches=AtomicInteger()
        val launchedOnMain=AtomicReference<Boolean?>()
        compose.setContent {
            val picker=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {}
            LaunchedEffect(Unit) {
                effect.set(currentCoroutineContext()[Job])
                entered.complete(Unit)
                release.await()
                launchedOnMain.set(Looper.myLooper()==Looper.getMainLooper())
                launches.incrementAndGet()
                picker.launch("clock-handoff-fixture.json")
            }
            Text("External picker handoff fixture")
        }
        compose.waitForIdle()
        assertTrue("The effect must await its background result",entered.isCompleted)
        var primary:Throwable?=null
        try {
            DocumentPickerUi.location(device,By.text("DistillPod test files"),synchronizeApp={
                compose.waitForIdle()
                // Complete strictly after the first drain; a one-shot idle cannot see it.
                if(released.compareAndSet(false,true))runBlocking {
                    withContext(Dispatchers.Default){release.complete(Unit)}
                }
            })
            assertEquals("The external activity must be launched once",1,launches.get())
            assertEquals("Picker launch must stay on Android main",true,launchedOnMain.get())
        } catch(failure:Throwable){primary=failure;throw failure}
        finally {
            try {
                effect.get()?.cancel()
                repeat(3) {
                    if(device.currentPackageName?.endsWith("documentsui")==true) {
                        device.pressBack()
                        device.wait(Until.gone(By.pkg(Pattern.compile(".*documentsui"))),1000)
                    }
                }
                compose.activityRule.scenario.close()
            } catch(cleanup:Throwable){if(primary!=null)primary.addSuppressed(cleanup) else throw cleanup}
        }
    }

    @Test fun lifecycleCollectionCanDisposeAfterBackgroundEmission() {
        requireEmulator()
        val value = MutableStateFlow("Before completion")
        compose.setContent {
            val text by value.collectAsStateWithLifecycle()
            Text(text)
        }
        compose.onNodeWithText("Before completion").assertIsDisplayed()
        runBlocking { withContext(Dispatchers.Default) { value.value = "After completion" } }
        compose.onNodeWithText("After completion").assertIsDisplayed()
        // Teardown must drain the queued lifecycle observer removal on Android main.
        compose.activityRule.scenario.close()
    }
}
