package dev.distillpod.android

import android.os.Bundle
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry

/** Content-free instrumentation breadcrumbs; never changes the original crash handling. */
internal class BackupCrashDiagnostics : AutoCloseable {
    @Volatile private var phase = "setup"
    private val testThread = Thread.currentThread()
    private val previous = Thread.getDefaultUncaughtExceptionHandler()
    private val handler = Thread.UncaughtExceptionHandler { thread, error ->
        try {
            emit("uncaught phase=$phase thread=${thread.id} main=${thread === Looper.getMainLooper().thread}")
            // Only app/test/framework stack symbols, no exception messages or local values.
            val stacks = Thread.getAllStackTraces().entries
                .filter { entry -> entry.key === thread || entry.key === testThread || entry.value.any {
                    it.className.startsWith("dev.distillpod.") || it.className.startsWith("androidx.compose.")
                } }
                .sortedWith(compareByDescending<Map.Entry<Thread, Array<StackTraceElement>>> { it.key === thread }
                    .thenByDescending { it.key === testThread }
                    .thenByDescending { entry -> entry.value.any { it.className.startsWith("androidx.compose.") } }
                    .thenBy { it.key.id })
            val report = buildString {
                appendLine("thread-stacks selected=${stacks.size} limit=16 frames=32")
                stacks.take(16).forEach { (owner, frames) ->
                    appendLine("thread=${owner.id} main=${owner === Looper.getMainLooper().thread}")
                    frames.filter { relevant(it.className) }.take(32).forEach {
                        appendLine("  ${it.className.take(200)}.${it.methodName.take(100)}:${it.lineNumber}")
                    }
                }
            }
            emit(report.take(48_000))
        } catch (_: Throwable) {
            // Diagnostics must never replace the exception being investigated.
        } finally {
            previous?.uncaughtException(thread, error)
        }
    }

    init {
        // Without a delegate, leave the platform fallback entirely unchanged.
        if (previous != null) Thread.setDefaultUncaughtExceptionHandler(handler)
        mark(if (previous != null) "installed" else "handler-unavailable")
    }

    fun mark(next: String) {
        phase = next
        try {
            val thread = Thread.currentThread()
            emit("phase=$next thread=${thread.id} main=${thread === Looper.getMainLooper().thread}")
        } catch (_: Throwable) { /* Breadcrumb transport must not alter test results. */ }
    }

    override fun close() {
        mark("diagnostics-close")
        if (Thread.getDefaultUncaughtExceptionHandler() === handler) {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    private fun relevant(name: String) = name.startsWith("dev.distillpod.") ||
        name.startsWith("androidx.") || name.startsWith("kotlin.") ||
        name.startsWith("kotlinx.") || name.startsWith("android.view.") ||
        name.startsWith("android.os.") || name.startsWith("org.junit.")

    private fun emit(message: String) {
        // STATUS_IN_PROGRESS with the standard stream key reaches `am instrument -w`.
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("stream", "[backup-diagnostic] $message\n")
        })
    }
}
