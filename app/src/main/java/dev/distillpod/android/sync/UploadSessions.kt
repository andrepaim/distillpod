package dev.distillpod.android.sync

import android.content.Context
import android.net.Uri
import dev.distillpod.core.sync.SyncOwner

/** Resumable URLs are credentials: app-private only, never cloud records, exports or diagnostics. */
class UploadSessions(context:Context) {
    private val preferences=context.getSharedPreferences("content-upload-sessions",Context.MODE_PRIVATE)
    private fun key(owner:SyncOwner,hash:String):String {
        require(listOf(owner.uid,owner.generation).all {it.matches(Regex("[A-Za-z0-9_-]{1,128}"))})
        require(hash.matches(Regex("[a-f0-9]{64}")))
        return "${owner.uid}/${owner.generation}/$hash"
    }
    fun read(owner:SyncOwner,hash:String):Uri?=preferences.getString(key(owner,hash),null)?.let(Uri::parse)
    fun save(owner:SyncOwner,hash:String,uri:Uri) {
        require(uri.scheme in setOf("http","https") && !uri.host.isNullOrBlank())
        if(read(owner,hash)!=uri)preferences.edit().putString(key(owner,hash),uri.toString()).apply()
    }
    fun clear(owner:SyncOwner,hash:String) {preferences.edit().remove(key(owner,hash)).apply()}
}
