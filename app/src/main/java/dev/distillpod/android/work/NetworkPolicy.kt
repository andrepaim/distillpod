package dev.distillpod.android.work

import androidx.work.Constraints
import androidx.work.NetworkType
import java.util.concurrent.TimeUnit

/** Copy the other restrictions using the public WorkManager API; [charging] replaces the charging requirement. */
internal fun Constraints.withNetworkType(network: NetworkType, charging: Boolean = requiresCharging()): Constraints = Constraints.Builder()
    .setRequiredNetworkType(network)
    .setRequiresCharging(charging)
    .setRequiresDeviceIdle(requiresDeviceIdle())
    .setRequiresBatteryNotLow(requiresBatteryNotLow())
    .setRequiresStorageNotLow(requiresStorageNotLow())
    .apply {
        contentUriTriggers.forEach { addContentUriTrigger(it.uri, it.isTriggeredForDescendants) }
        if (contentTriggerUpdateDelayMillis >= 0) setTriggerContentUpdateDelay(contentTriggerUpdateDelayMillis, TimeUnit.MILLISECONDS)
        if (contentTriggerMaxDelayMillis >= 0) setTriggerContentMaxDelay(contentTriggerMaxDelayMillis, TimeUnit.MILLISECONDS)
    }.build()
