package dev.distillpod.android

import android.os.Looper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.robolectric.Shadows.shadowOf

/** After clearing the store, wait for Room collectors to stop before closing their database. */
internal suspend fun Job.awaitViewModelTeardown() {
    val mainLooper = shadowOf(Looper.getMainLooper())
    withTimeout(5000) {
        mainLooper.idle()
        while (!isCompleted) {
            delay(10)
            mainLooper.idle()
        }
    }
}
