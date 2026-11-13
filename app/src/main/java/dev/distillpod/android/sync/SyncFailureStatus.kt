package dev.distillpod.android.sync

import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.storage.StorageException
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

/** The cloud deterministically refused this one operation; retrying it unchanged cannot succeed. */
class SyncOperationRejected(cause:Exception):Exception("The cloud rejected this change.",cause)

/** Storage rules refusing a create after the object read was allowed; authentication and quota failures stay retryable. */
internal fun storageUploadRejected(error:StorageException):Boolean=error.errorCode==StorageException.ERROR_NOT_AUTHORIZED
