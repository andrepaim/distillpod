package dev.distillpod.android.sync

import java.io.IOException

/** A transport failure is not a rejected operation. Retry policy remains with the worker. */
internal fun syncFailureStatus(error:Exception):String = when {
    error is IOException -> "offline"
    SyncBuild.transportUnavailable(error) -> "offline"
    else -> "error"
}

/** The cloud deterministically refused this one operation; retrying it unchanged cannot succeed. */
class SyncOperationRejected(cause:Exception):Exception("The cloud rejected this change.",cause)
