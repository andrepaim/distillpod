package dev.distillpod.android.diagnostics

import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicInteger

/** Producer fixtures exercise real files/publication without a physical-media latency dependency. */
internal class RecordingDiagnosticFileForce(private val atForce: (FileDescriptor) -> Unit = {}) : DiagnosticFileForce {
    private val count = AtomicInteger()
    val calls: Int get() = count.get()
    override fun force(descriptor: FileDescriptor) {
        check(descriptor.valid())
        count.incrementAndGet()
        atForce(descriptor)
    }
}
