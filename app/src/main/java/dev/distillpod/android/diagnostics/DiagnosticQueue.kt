package dev.distillpod.android.diagnostics

import java.io.File
import java.util.UUID

/** Separate, bounded outbox. Only sanitized SDK envelopes may enter this store. */
internal class DiagnosticQueue(private val directory: File, private val now: () -> Long = System::currentTimeMillis, private val onDrop: () -> Unit = {}) {
    @Synchronized fun append(bytes: ByteArray):Boolean {
        if(bytes.size > 256 * 1024) {onDrop();return false}
        directory.mkdirs()
        val target = File(directory, "${now()}-${UUID.randomUUID()}.envelope")
        val pending = File(directory, target.name + ".tmp")
        java.io.FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
        check(pending.renameTo(target))
        prune();return target.exists()
    }
    @Synchronized fun files(): List<File> { prune(); return directory.listFiles()?.filter {it.extension=="envelope"}?.sortedBy {it.name}.orEmpty() }
    @Synchronized fun clear() { directory.listFiles()?.forEach {it.delete()} }
    private fun prune() {
        val files=directory.listFiles()?.sortedBy {it.name}.orEmpty()
        var size=files.sumOf {it.length()};var count=files.size
        for(file in files) if(file.lastModified()<now()-72*60*60*1000L || size>5*1024*1024 || count>1000 || file.extension=="tmp") {
            size-=file.length();count--;if(file.delete())onDrop()
        }
    }
}
