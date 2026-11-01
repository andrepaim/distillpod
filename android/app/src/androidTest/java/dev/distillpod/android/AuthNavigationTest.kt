package dev.distillpod.android

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun launcherReentryReusesTheExistingConnectionScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val original = compose.activity
        instrumentation.runOnMainSync {
            original.startActivity(Intent(original, MainActivity::class.java))
        }
        var resumed: android.app.Activity? = null
        val until = SystemClock.elapsedRealtime() + 5000
        while(SystemClock.elapsedRealtime() < until) {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { it is MainActivity }
            }
            if(resumed != null) break
            SystemClock.sleep(100)
        }
        assertSame("Launcher must reuse the Activity that owns the pending authorization", original, resumed)
    }
}
