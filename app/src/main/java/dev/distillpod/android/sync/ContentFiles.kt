package dev.distillpod.android.sync

import android.content.Context
import android.util.AtomicFile
import dev.distillpod.core.sync.*
import java.io.File

/** Account-scoped staging survives process death. Only verified, bounded files enter this cache. */
class ContentFiles(context:Context,private val root:File=File(context.filesDir,"cloud-content")) {
    private fun file(owner:SyncOwner,hash:String):File {
        require(listOf(owner.uid,owner.generation).all {it.matches(Regex("[A-Za-z0-9_-]{1,128}"))})
        require(hash.matches(Regex("[a-f0-9]{64}")))
        return File(root,"${owner.uid}/${owner.generation}/$hash.gz")
    }
    internal fun path(owner:SyncOwner,hash:String)=file(owner,hash).absolutePath
    fun save(owner:SyncOwner,artifact:ContentArtifact) {
        ContentArtifacts.decode(artifact)
        val target=file(owner,artifact.hash);target.parentFile!!.mkdirs()
        val atomic=AtomicFile(target);val stream=atomic.startWrite()
        try {stream.write(artifact.compressed);atomic.finishWrite(stream)}catch(error:Throwable){atomic.failWrite(stream);throw error}
    }
    fun read(owner:SyncOwner,hash:String,expandedBytes:Long):ContentArtifact? {
        val target=file(owner,hash)
        if(!target.isFile)return null
        return try {
            require(target.length() in 1..ContentArtifacts.MAX_COMPRESSED.toLong())
            ContentArtifact(1,hash,expandedBytes,target.readBytes()).also {ContentArtifacts.decode(it)}
        } catch(error:IllegalArgumentException) {
            target.delete();null
        }
    }
}
