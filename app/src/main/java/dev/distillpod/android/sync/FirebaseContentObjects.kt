package dev.distillpod.android.sync

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import dev.distillpod.core.sync.*
import kotlinx.coroutines.tasks.await

interface ContentObjects {
    suspend fun probe(owner:SyncOwner) {}
    suspend fun put(owner:SyncOwner,artifact:ContentArtifact)
    suspend fun get(owner:SyncOwner,hash:String,expandedBytes:Long):ContentArtifact
}

/** Immutable content-addressed objects. The configured Firebase app shares the sync account. */
class FirebaseContentObjects(private val auth:FirebaseAuth,private val storage:FirebaseStorage,private val files:ContentFiles?=null,private val sessions:UploadSessions?=null):ContentObjects {
    private fun objectRef(owner:SyncOwner,hash:String):com.google.firebase.storage.StorageReference {
        check(auth.currentUser?.uid==owner.uid) {"Reconnect the original library account"}
        require(hash.matches(Regex("[a-f0-9]{64}")))
        require(listOf(owner.uid,owner.generation).all {it.matches(Regex("[A-Za-z0-9_-]{1,128}"))})
        return storage.reference.child("users/${owner.uid}/${owner.generation}/$hash.gz")
    }
    override suspend fun probe(owner:SyncOwner) {
        objectRef(owner,"0".repeat(64)).parent!!.list(1).await()
    }
    override suspend fun put(owner:SyncOwner,artifact:ContentArtifact) {
        // Verify before network; no malformed compressed content can be published by this adapter.
        ContentArtifacts.decode(artifact)
        val ref=objectRef(owner,artifact.hash)
        try {
            val metadata=ref.metadata.await()
            check(metadata.getCustomMetadata("hash")==artifact.hash && metadata.getCustomMetadata("expandedBytes")==artifact.expandedBytes.toString()
                && metadata.getCustomMetadata("schema")=="1") {"Cloud artifact identity changed"}
            sessions?.clear(owner,artifact.hash)
            return
        } catch(error:StorageException) {
            if(error.errorCode!=StorageException.ERROR_OBJECT_NOT_FOUND)throw error
        }
        objectRef(owner,artifact.hash)
        val metadata=StorageMetadata.Builder().setContentType("application/gzip").setCustomMetadata("hash",artifact.hash)
            .setCustomMetadata("expandedBytes",artifact.expandedBytes.toString()).setCustomMetadata("schema","1").build()
        try {
            if(files==null)ref.putBytes(artifact.compressed,metadata).await()
            else {
                val uri=android.net.Uri.fromFile(java.io.File(files.path(owner,artifact.hash)))
                val session=sessions?.read(owner,artifact.hash)
                val upload=if(session==null)ref.putFile(uri,metadata) else ref.putFile(uri,metadata,session)
                upload.addOnProgressListener {snapshot->snapshot.uploadSessionUri?.let {sessions?.save(owner,artifact.hash,it)}}
                try {upload.await()}catch(cancelled:kotlinx.coroutines.CancellationException){upload.cancel();throw cancelled}
            }
        } catch(error:StorageException) {
            // An expired upload URI can be discarded; authentication/permission failures are not retried as fresh uploads.
            if(error.httpResultCode==410)sessions?.clear(owner,artifact.hash)
            else {
                val existing=runCatching {ref.metadata.await()}.getOrNull()
                if(existing?.getCustomMetadata("hash")!=artifact.hash || existing.getCustomMetadata("expandedBytes")!=artifact.expandedBytes.toString())throw error
            }
            if(error.httpResultCode==410)throw error
        }
        sessions?.clear(owner,artifact.hash)
        objectRef(owner,artifact.hash)
    }
    override suspend fun get(owner:SyncOwner,hash:String,expandedBytes:Long):ContentArtifact {
        require(expandedBytes in 1..ContentArtifacts.MAX_EXPANDED.toLong())
        val ref=objectRef(owner,hash);val metadata=ref.metadata.await()
        require(metadata.sizeBytes in 1..ContentArtifacts.MAX_COMPRESSED.toLong())
        require(metadata.getCustomMetadata("hash")==hash && metadata.getCustomMetadata("expandedBytes")==expandedBytes.toString() && metadata.getCustomMetadata("schema")=="1")
        objectRef(owner,hash)
        val bytes=ref.getBytes(ContentArtifacts.MAX_COMPRESSED.toLong()).await()
        objectRef(owner,hash)
        return ContentArtifact(1,hash,expandedBytes,bytes).also {ContentArtifacts.decode(it)}
    }
}
