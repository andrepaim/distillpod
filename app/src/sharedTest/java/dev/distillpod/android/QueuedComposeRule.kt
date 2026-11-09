package dev.distillpod.android

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.IdlingResource
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Finish lifecycle cleanup on main before Compose validates its remaining test coroutines. */
@OptIn(ExperimentalCoroutinesApi::class)
class QueuedComposeActivityRule<A : ComponentActivity>(
    private val delegate: ActivityScenarioRule<A>,
    private val scheduler: TestCoroutineScheduler,
) : TestRule {
    val scenario: ActivityScenario<A> get() = delegate.scenario

    fun activity(): A {
        var current: A? = null
        scenario.onActivity { current = it }
        return checkNotNull(current) { "Compose fixture activity is unavailable" }
    }

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            var primary: Throwable? = null
            try {
                delegate.apply(base, description).evaluate()
            } catch (failure: Throwable) {
                primary = failure
                throw failure
            } finally {
                try {
                    if (Looper.myLooper() == Looper.getMainLooper()) scheduler.runCurrent()
                    else InstrumentationRegistry.getInstrumentation().runOnMainSync { scheduler.runCurrent() }
                } catch (cleanup: Throwable) {
                    if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }
}

/** Compose 1.7.8: queue background resumptions, then drain them on Android main before idle. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
internal inline fun <reified A : ComponentActivity> createQueuedAndroidComposeRule() =
    StandardTestDispatcher().let { dispatcher ->
        val launcher = QueuedComposeActivityRule(ActivityScenarioRule(A::class.java), dispatcher.scheduler)
        AndroidComposeTestRule(activityRule = launcher, effectContext = dispatcher,
            activityProvider = { it.activity() }).also { rule ->
            // Register before the environment installs its own Compose idling resource.
            // Its frame/layout check must see any work published by these queued effects.
            rule.registerIdlingResource(object : IdlingResource {
                override val isIdleNow: Boolean
                    get() {
                        if (rule.mainClock.autoAdvance) {
                            rule.runOnUiThread { dispatcher.scheduler.runCurrent() }
                        }
                        return true
                    }
            })
        }
    }
