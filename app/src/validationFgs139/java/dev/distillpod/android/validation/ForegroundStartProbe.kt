package dev.distillpod.android.validation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.distillpod.android.voice.HeadphoneVoiceService

/**
 * Isolated #139 probe, compiled only into dev.distillpod.android.validation.fgs139.
 * Makes the same in-process startForegroundService() call as HeadphoneVoiceService.arm(),
 * but without arm()'s key check: that is the state after the key is removed in between.
 */
class ForegroundStartProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        check(context.packageName == "dev.distillpod.android.validation.fgs139")
        ContextCompat.startForegroundService(context, Intent(context, HeadphoneVoiceService::class.java))
    }
}
