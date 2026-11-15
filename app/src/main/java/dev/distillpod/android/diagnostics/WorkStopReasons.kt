package dev.distillpod.android.diagnostics

import androidx.work.WorkInfo

/**
 * Finite, content-free categories for why WorkManager stopped a worker (#124). `not_worker` means
 * the execution had no worker to ask; `not_stopped` means the coroutine was cancelled although
 * WorkManager had not stopped the worker (for example process-scoped cancellation).
 */
internal object WorkStopReasons {
    fun category(code:Int?):String = when(code) {
        null -> "not_worker"
        WorkInfo.STOP_REASON_NOT_STOPPED -> "not_stopped"
        WorkInfo.STOP_REASON_UNKNOWN -> "unknown"
        WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "cancelled_by_app"
        WorkInfo.STOP_REASON_PREEMPT -> "preempted"
        WorkInfo.STOP_REASON_TIMEOUT -> "timeout"
        WorkInfo.STOP_REASON_DEVICE_STATE -> "device_state"
        WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "battery_low"
        WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> "charging_required"
        WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "connectivity"
        WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE -> "device_idle"
        WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "storage_low"
        WorkInfo.STOP_REASON_QUOTA -> "quota"
        WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> "background_restriction"
        WorkInfo.STOP_REASON_APP_STANDBY -> "app_standby"
        WorkInfo.STOP_REASON_USER -> "user"
        WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> "system_processing"
        WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED -> "app_launch_estimate_changed"
        WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> "foreground_service_timeout"
        else -> "other"
    }
    /** Every value [category] can return, plus `none` for executions that were not interrupted. */
    val VALUES=setOf("none","not_worker","not_stopped","unknown","cancelled_by_app","preempted","timeout","device_state",
        "battery_low","charging_required","connectivity","device_idle","storage_low","quota","background_restriction",
        "app_standby","user","system_processing","app_launch_estimate_changed","foreground_service_timeout","other")
}
