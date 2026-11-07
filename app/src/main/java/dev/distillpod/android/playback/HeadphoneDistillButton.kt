package dev.distillpod.android.playback

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent

/** Called on the session's application thread. The earbuds, not the app, count taps. */
internal class HeadphoneDistillButton(private val keyCode: Int = KeyEvent.KEYCODE_MEDIA_PREVIOUS,
    private val report: (String) -> Unit = {},
    private val now: () -> Long = SystemClock::uptimeMillis) {
    private data class Press(val device: Int, val downTime: Long)
    private var consumedPress: Press? = null
    private var lastPress: Press? = null
    private var lastCaptureAt: Long? = null

    @Suppress("DEPRECATION")
    fun handle(intent: Intent, enabled: Boolean, capture: () -> Unit): Boolean {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return false
        val event = runCatching { intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) }.getOrNull()
            ?: return false
        if (event.keyCode != keyCode ||
            event.action !in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) return false
        val press = Press(event.deviceId, event.downTime)
        // Finish consuming a remapped press even when the setting changes before release.
        if (!enabled) {
            report("disabled")
            if (press != consumedPress) return false
            if (event.action == KeyEvent.ACTION_UP) consumedPress = null
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) {
            if (press == consumedPress) consumedPress = null
            return true
        }
        consumedPress = press
        val time = now()
        val duplicate = press == lastPress || lastCaptureAt?.let { time - it < 750 } == true
        lastPress = press
        if (event.repeatCount == 0 && !event.isCanceled && !duplicate) {
            lastCaptureAt = time
            report("accepted")
            capture()
        }
        if(duplicate)report("duplicate") else if(event.isCanceled)report("cancelled") else if(event.repeatCount>0)report("repeat")
        return true
    }
}
