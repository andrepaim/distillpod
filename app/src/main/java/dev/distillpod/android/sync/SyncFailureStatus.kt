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

/**
 * Classifies a failed create of an immutable content object. Returns null when the object already
 * holds this artifact. A refusal is specific to this object only when re-reading it is allowed.
 */
internal suspend fun uploadFailure(error:StorageException,hash:String,expandedBytes:Long,reread:suspend ()->com.google.firebase.storage.StorageMetadata):Exception? {
    val existing=try { reread() }
        catch(cancelled:kotlinx.coroutines.CancellationException) { throw cancelled }
        catch(missing:StorageException) { if(missing.errorCode!=StorageException.ERROR_OBJECT_NOT_FOUND)return error;null }
        catch(_:Exception) { return error }
    if(existing?.getCustomMetadata("hash")==hash && existing.getCustomMetadata("expandedBytes")==expandedBytes.toString())return null
    return if(storageUploadRejected(error))SyncOperationRejected(error) else error
}

/** The server refused this operation's own staged writes as malformed (for example an oversized document). */
internal fun firestoreWriteRejected(error:Exception,staged:Boolean):Boolean=
    staged && error is FirebaseFirestoreException && error.code==FirebaseFirestoreException.Code.INVALID_ARGUMENT
