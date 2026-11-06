package dev.distillpod.android.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The foreground service owns this binding; a media key must never start a background mic service. */
class HeadphoneVoiceShortcut {
    private val mutable = MutableStateFlow(false)
    val ready = mutable.asStateFlow()
    private var start: (() -> Unit)? = null
    fun attach(action: () -> Unit): AutoCloseable {
        start = action; mutable.value = true
        return AutoCloseable { if (start === action) { start = null; mutable.value = false } }
    }
    fun ask(): Boolean = start?.let { it(); true } ?: false
}

internal enum class HeadphoneNextAction { START_PHONE, START_CAR, DONE, CANCEL, WAIT, UNAVAILABLE }

/** null means car connection discovery has not completed: don't guess a microphone source. */
internal fun headphoneNextAction(state: VoiceState, carConnected: Boolean?, carReady: Boolean,
    phoneReady: Boolean): HeadphoneNextAction = when {
    state.phase == "Listening" -> HeadphoneNextAction.DONE
    state.phase == "Finishing" -> HeadphoneNextAction.WAIT
    state.active -> HeadphoneNextAction.CANCEL
    carConnected == true -> if (carReady) HeadphoneNextAction.START_CAR else HeadphoneNextAction.UNAVAILABLE
    carConnected == false && phoneReady -> HeadphoneNextAction.START_PHONE
    else -> HeadphoneNextAction.UNAVAILABLE
}
