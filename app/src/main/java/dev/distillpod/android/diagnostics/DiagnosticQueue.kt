package dev.distillpod.android.diagnostics

import java.io.File
import java.util.UUID

/** Separate, bounded outbox. Only sanitized SDK envelopes may enter this store. */
internal class DiagnosticQueue private constructor(private val directory: File, private val now: () -> Long,
    private val onDrop: () -> Unit, private val fileForce: DiagnosticFileForce) {
    constructor(directory: File, now: () -> Long = System::currentTimeMillis, onDrop: () -> Unit = {}) :
        this(directory, now, onDrop, NativeDiagnosticFileForce)

    companion object {
        internal fun withFileForce(directory: File, fileForce: DiagnosticFileForce,
            now: () -> Long = System::currentTimeMillis, onDrop: () -> Unit = {}) =
            DiagnosticQueue(directory, now, onDrop, fileForce)
    }
    @Synchronized fun append(bytes: ByteArray, priority: Boolean = false):Boolean {
        if(bytes.size > 256 * 1024) {onDrop();return false}
        directory.mkdirs()
        val target = File(directory, "${now()}-${UUID.randomUUID()}-"+(if(priority)"priority-" else "")+".envelope")
        val pending = File(directory, target.name + ".tmp")
        java.io.FileOutputStream(pending).use { it.write(bytes); fileForce.force(it.fd) }
        check(pending.renameTo(target))
        prune();return target.exists()
    }
    @Synchronized fun files(): List<File> { prune(); return directory.listFiles()?.filter {it.extension=="envelope"}?.sortedBy {it.name}.orEmpty() }
    @Synchronized fun clear() { directory.listFiles()?.forEach {it.delete()} }
    private fun prune() {
        val existing=directory.listFiles().orEmpty()
        // Expired priority files must not evict valid routine files before being removed.
        existing.filter {it.lastModified()<now()-72*60*60*1000L || it.extension=="tmp"}
            .forEach {if(it.delete())onDrop()}
        val files=directory.listFiles()?.sortedWith(compareBy<File> {it.name.contains("priority-")}.thenBy {it.name}).orEmpty()
        var size=files.sumOf {it.length()};var count=files.size
        for(file in files) if(size>5*1024*1024 || count>1000) {
            val removedSize=file.length()
            if(file.delete()){size-=removedSize;count--;onDrop()}
        }
    }
}
