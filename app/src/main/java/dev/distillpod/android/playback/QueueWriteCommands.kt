package dev.distillpod.android.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** Same-application protocol; never advertised to car or other external controllers. */
internal object QueueWriteCommands {
    val BARRIER = SessionCommand("dev.distillpod.android.QUEUE_WRITES_PROCESSED", Bundle.EMPTY)
}
