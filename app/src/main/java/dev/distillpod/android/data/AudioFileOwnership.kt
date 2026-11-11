package dev.distillpod.android.data

import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** Coordinates canonical local copies without holding a monitor across file IO. */
internal class AudioFileOwnership(private val changed: () -> Unit = {}) {
    class Selection internal constructor(val path: String) {
        internal var owners = 0
        internal var revoked = false
    }
    class Lease internal constructor(private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }
    class Removal internal constructor(private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    private val monitor = Any()
    // Tags and leases retain their generation. Abandoned preparations can be collected.
    private val copies = mutableMapOf<String, WeakReference<Selection>>()
    private val removing = mutableSetOf<String>()

    /** Register before stat on the IO thread, so removal can revoke this exact generation. */
    fun select(path: String): Selection? = synchronized(monitor) {
        prune()
        if (path in removing) null
        else copies[path]?.get() ?: Selection(path).also { copies[path] = WeakReference(it) }
    }

    /** Called after stat failed. A different generation or an owned copy stays intact. */
    fun discard(selection: Selection) = synchronized(monitor) {
        if (selection.owners == 0 && copies[selection.path]?.get() === selection) {
            selection.revoked = true
            copies.remove(selection.path)
        }
        Unit
    }

    fun acquire(selection: Selection): Lease? {
        val lease = synchronized(monitor) {
            if (selection.revoked || selection.path in removing || copies[selection.path]?.get() !== selection) return null
            selection.owners++
            Lease {
                synchronized(monitor) {
                    check(selection.owners > 0)
                    selection.owners--
                }
                changed()
            }
        }
        changed()
        return lease
    }

    /** A failed batch has no effects. The reservation lasts through file and database work. */
    fun reserve(paths: Collection<String>): Removal? {
        val distinct = paths.toSet()
        val removal = synchronized(monitor) {
            prune()
            if (distinct.any { it in removing || (copies[it]?.get()?.owners ?: 0) > 0 }) return null
            distinct.forEach {
                copies[it]?.get()?.revoked = true
                copies.remove(it)
                removing.add(it)
            }
            Removal {
                synchronized(monitor) { removing.removeAll(distinct) }
                changed()
            }
        }
        changed()
        return removal
    }

    fun busy(path: String): Boolean = synchronized(monitor) {
        path in removing || (copies[path]?.get()?.owners ?: 0) > 0
    }

    private fun prune() {
        copies.entries.removeAll { it.value.get() == null }
    }
}
