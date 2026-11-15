package dev.distillpod.android.playback

import android.util.Log

/**
 * Queue restore and persistence decisions (#251), under one stable logcat tag. Messages carry only
 * episode ids, counts, revisions and reasons, never titles or other library content. Queue events are
 * rare (never per progress tick), so this stays on in every build; `setprop log.tag.DistillQueue
 * SUPPRESS` silences it.
 */
internal object QueueLog {
    const val TAG = "DistillQueue"

    fun i(message: () -> String) {
        // Plain JVM unit tests have no Android logger.
        runCatching { if (Log.isLoggable(TAG, Log.INFO)) Log.i(TAG, message()) }
    }

    /** At most [limit] ids, then the total, so a long queue cannot flood the log. */
    fun ids(values: List<String>, limit: Int = 10): String =
        if (values.size <= limit) values.joinToString(",", "[", "]")
        else values.take(limit).joinToString(",", "[", ",…+${values.size - limit}]")
}
