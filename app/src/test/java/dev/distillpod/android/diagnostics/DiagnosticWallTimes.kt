package dev.distillpod.android.diagnostics

import org.junit.Assert.assertTrue

internal data class DiagnosticWallTimes(val observedAtMs: Long, val emittedAtMs: Long)

/** Separate wall-clock samples are evidence, not a monotonic execution clock. */
internal fun assertDiagnosticWallTimes(fields: Map<String, Any>): DiagnosticWallTimes {
    fun wallTime(name: String): Long {
        assertTrue("Missing $name", fields.containsKey(name))
        val value = fields[name]
        assertTrue("$name must be numeric and finite", value is Number && value.toDouble().isFinite())
        val millis = (value as Number).toLong()
        assertTrue("$name must be positive wall-clock milliseconds", millis > 0)
        return millis
    }
    return DiagnosticWallTimes(wallTime("observed_at_ms"), wallTime("emitted_at_ms"))
}
