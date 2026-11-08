package dev.distillpod.android.work

import androidx.work.Constraints
import androidx.work.NetworkType
import java.util.concurrent.TimeUnit

/** Copy non-network restrictions using the public WorkManager API. */
internal fun Constraints.withNetworkPolicy(wifiOnly: Boolean): Constraints = Constraints.Builder()
    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
    .setRequiresCharging(requiresCharging())
    .setRequiresDeviceIdle(requiresDeviceIdle())
    .setRequiresBatteryNotLow(requiresBatteryNotLow())
    .setRequiresStorageNotLow(requiresStorageNotLow())
    .apply {
        contentUriTriggers.forEach { addContentUriTrigger(it.uri, it.isTriggeredForDescendants) }
        if (contentTriggerUpdateDelayMillis >= 0) setTriggerContentUpdateDelay(contentTriggerUpdateDelayMillis, TimeUnit.MILLISECONDS)
        if (contentTriggerMaxDelayMillis >= 0) setTriggerContentMaxDelay(contentTriggerMaxDelayMillis, TimeUnit.MILLISECONDS)
    }.build()
