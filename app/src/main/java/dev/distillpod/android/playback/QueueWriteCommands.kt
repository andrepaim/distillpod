package dev.distillpod.android.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** Same-application protocol; never advertised to car or other external controllers. */
internal object QueueWriteCommands {
    const val TOKEN = "queueChoice"
    val IMPORT = SessionCommand("dev.distillpod.android.CHOOSE_CLOUD_QUEUE", Bundle.EMPTY)
    val BARRIER = SessionCommand("dev.distillpod.android.QUEUE_WRITES_PROCESSED", Bundle.EMPTY)
}
