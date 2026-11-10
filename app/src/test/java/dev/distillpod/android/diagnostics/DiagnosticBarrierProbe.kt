package dev.distillpod.android.diagnostics

import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicReference
import org.robolectric.util.ReflectionHelpers

/** Test-only observation; delegates the existing factory, barrier and five-second bound. */
internal class DiagnosticBarrierProbe(private val diagnostics: Diagnostics) {
    internal val executor: ThreadPoolExecutor = ReflectionHelpers.getField(diagnostics, "executor")
    internal val worker = AtomicReference<Thread>()
    init {
        val original = executor.threadFactory
        executor.threadFactory = java.util.concurrent.ThreadFactory { command ->
            original.newThread(command).also(worker::set)
        }
    }

    fun awaitIdle(barrier: () -> Unit = diagnostics::awaitIdle) {
        try { barrier() }
        catch (failure: Throwable) {
            try { failure.addSuppressed(AssertionError(snapshot())) }
            catch (diagnosticFailure: Throwable) { failure.addSuppressed(diagnosticFailure) }
            throw failure
        }
    }

    private fun snapshot(): String = buildString {
        append("Fixture diagnostics snapshot: pool=${executor.poolSize} active=${executor.activeCount} ")
        append("queue=${executor.queue.size} remaining=${executor.queue.remainingCapacity()} ")
        append("tasks=${executor.taskCount} completed=${executor.completedTaskCount} ")
        append("shutdown=${executor.isShutdown} terminated=${executor.isTerminated}\n")
        val futures = executor.queue.filterIsInstance<java.util.concurrent.Future<*>>()
        append("queuedFutures=${futures.size} done=${futures.count { it.isDone }} cancelled=${futures.count { it.isCancelled }}\n")
        // Identify this executor's actual worker, rather than other fixtures' same-name threads.
        // Only JVM-local thread/monitor identities and public code frames; no names, values or messages.
        // Android's compile bootclasspath omits java.management; this runs only in JVM tests.
        val mx = Class.forName("java.lang.management.ManagementFactory")
            .getMethod("getThreadMXBean").invoke(null)
        val getInfo = Class.forName("java.lang.management.ThreadMXBean")
            .getMethod("getThreadInfo", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val infoType = Class.forName("java.lang.management.ThreadInfo")
        val workerThread = worker.get()
        val mainThread = android.os.Looper.getMainLooper().thread
        val pending = java.util.ArrayDeque<Pair<Long, String>>()
        workerThread?.let { pending.add(it.id to "worker") }
        pending.add(mainThread.id to "main")
        val seen = mutableSetOf<Long>()
        while (pending.isNotEmpty() && seen.size < 12) {
            val (id, label) = pending.removeFirst()
            if (!seen.add(id)) continue
            val info = getInfo.invoke(mx, id, 32)
            if (info == null) { append("$label: unavailable\n"); continue }
            fun value(method: String) = infoType.getMethod(method).invoke(info)
            val owner = value("getLockOwnerId") as Long
            append("$label: jvmThread=$id state=${value("getThreadState")} blocked=${value("getBlockedCount")} waited=${value("getWaitedCount")}")
            append(" lock=${value("getLockName")} owner=$owner\n")
            @Suppress("UNCHECKED_CAST")
            val frames = value("getStackTrace") as Array<StackTraceElement>
            frames.take(32).forEach { append("  ${it.className}.${it.methodName}:${it.lineNumber}\n") }
            if (owner > 0) pending.add(owner to "lock-owner")
        }
        if (workerThread == null) append("worker: factory has not created a thread\n")
    }
}
