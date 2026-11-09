package dev.distillpod.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.IdlingResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher

/** Compose 1.7.8: queue background resumptions, then drain them on Android main before idle. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
internal inline fun <reified A : ComponentActivity> createQueuedAndroidComposeRule() =
    StandardTestDispatcher().let { dispatcher ->
        createAndroidComposeRule<A>(effectContext = dispatcher).also { rule ->
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
