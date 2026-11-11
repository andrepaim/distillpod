package dev.distillpod.android.diagnostics

import java.io.FileDescriptor

/** Per-instance durability boundary; ordinary application construction always uses native force. */
internal fun interface DiagnosticFileForce {
    fun force(descriptor: FileDescriptor)
}

internal object NativeDiagnosticFileForce : DiagnosticFileForce {
    override fun force(descriptor: FileDescriptor) = descriptor.sync()
}
