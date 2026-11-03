package dev.distillpod.core

/** Remove a repeated overlap only when multiple normalized words agree. Timings remain segment-level. */
fun trimRepeatedOverlap(previous: String, next: String): String {
    val before = previous.trim().split(Regex("\\s+")).takeLast(40)
    val after = next.trim().split(Regex("\\s+"))
    fun normalize(value: String) = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
    for (count in minOf(before.size, after.size, 40) downTo 2) {
        val a = before.takeLast(count).map(::normalize)
        val b = after.take(count).map(::normalize)
        if (a == b && a.any { it.isNotBlank() }) return after.drop(count).joinToString(" ")
    }
    return next.trim()
}
