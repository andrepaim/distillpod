package dev.distillpod.android.sync

/** Set by the test runner before Application creation; SDK tests inject demo clients explicitly. */
internal object SubscriptionCloudAccess {
    @Volatile var instrumentation: Boolean = false
}
