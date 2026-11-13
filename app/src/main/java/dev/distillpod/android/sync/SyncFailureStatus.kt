package dev.distillpod.android.sync

import com.google.firebase.firestore.FirebaseFirestoreException
import java.io.IOException

/** A transport failure is not a rejected operation. Retry policy remains with the worker. */
internal fun syncFailureStatus(error:Exception):String = when {
    error is IOException -> "offline"
    error is FirebaseFirestoreException && error.code in setOf(
        FirebaseFirestoreException.Code.UNAVAILABLE,
        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED
    ) -> "offline"
    else -> "error"
}
