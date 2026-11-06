package dev.distillpod.android.voice

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** Only a host-bound car Session can open the car UI and obtain its microphone. */
class CarVoiceEntry {
    private val state = kotlinx.coroutines.flow.MutableStateFlow(false)
    val connected: kotlinx.coroutines.flow.StateFlow<Boolean> = state
    private var launch: (() -> Unit)? = null
    fun attach(action: () -> Unit): AutoCloseable {
        launch = action; state.value = true
        return AutoCloseable { if (launch === action) { launch = null; state.value = false } }
    }
    fun open(): Boolean {
        val action = launch ?: return false
        return runCatching { action(); true }.getOrDefault(false)
    }
    companion object {
        val ASK = SessionCommand("dev.distillpod.android.ASK_EPISODE", Bundle.EMPTY)
        val TOKEN = SessionCommand("dev.distillpod.android.CAR_PLATFORM_TOKEN", Bundle.EMPTY)
        const val TOKEN_EXTRA = "platform_token"
        const val ASK_ACTION = "dev.distillpod.android.action.ASK_EPISODE"
    }
}
