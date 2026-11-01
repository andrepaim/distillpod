package dev.distillpod.android

import android.content.ComponentName
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlaybackDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun importedClipPlaysInBackgroundAndSupportsSystemPauseAndSeek() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = compose.activity
        instrumentation.context.assets.open("jfk.wav").use { input -> context.getFileStreamPath("clip.wav").outputStream().use(input::copyTo) }
        compose.onNodeWithText("Play imported clip").performScrollTo().performClick()
        val token = SessionToken(context, ComponentName(context.packageName, "${context.packageName}.PlaybackService"))
        val future = MediaController.Builder(context, token).buildAsync()
        val controller = future.get(10, TimeUnit.SECONDS)
        fun awaitState(predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 8000
            while(SystemClock.elapsedRealtime() < end) {
                var accepted = false
                instrumentation.runOnMainSync { accepted = predicate() }
                if(accepted) return
                SystemClock.sleep(100)
            }
            fail("Playback state did not reach its expected value")
        }
        try {
            awaitState { controller.isPlaying }
            UiDevice.getInstance(instrumentation).pressHome()
            awaitState { controller.isPlaying }
            instrumentation.runOnMainSync { controller.pause(); controller.seekTo(2000) }
            awaitState { !controller.isPlaying && controller.currentPosition in 1900..2200 }
        } finally {
            instrumentation.runOnMainSync { controller.stop(); controller.release() }
        }
    }
}
