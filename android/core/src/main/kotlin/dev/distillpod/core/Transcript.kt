package dev.distillpod.core

data class TimeRange(val startMs: Long, val endMs: Long) {
    init { require(startMs >= 0 && endMs >= startMs) }
}
data class Word(val text: String, val startMs: Long, val endMs: Long) {
    init { require(startMs >= 0 && endMs >= startMs) }
}
data class Excerpt(val range: TimeRange, val text: String, val complete: Boolean)
data class Transcript(val mediaFingerprint: String, val words: List<Word>, val coverage: List<TimeRange>) {
    fun matchesMedia(fingerprint: String) = mediaFingerprint == fingerprint
    fun excerpt(positionMs: Long, windowMs: Long = 60000): Excerpt {
        require(positionMs >= 0 && windowMs > 0)
        val range = TimeRange((positionMs - windowMs).coerceAtLeast(0), positionMs)
        val text = words.filter { it.startMs >= range.startMs && it.endMs <= range.endMs }
            .sortedBy { it.startMs }.joinToString(" ") { it.text.trim() }
        val complete = normalizeRanges(coverage, Long.MAX_VALUE).any { it.startMs <= range.startMs && it.endMs >= range.endMs }
        return Excerpt(range, text, complete)
    }
}
fun normalizeRanges(ranges: List<TimeRange>, durationMs: Long): List<TimeRange> {
    require(durationMs >= 0)
    val result = mutableListOf<TimeRange>()
    ranges.map { TimeRange(it.startMs.coerceAtMost(durationMs), it.endMs.coerceAtMost(durationMs)) }
        .filter { it.endMs > it.startMs }.sortedBy { it.startMs }.forEach { r ->
            val last = result.lastOrNull()
            if (last != null && r.startMs <= last.endMs) result[result.lastIndex] = TimeRange(last.startMs, maxOf(last.endMs, r.endMs))
            else result.add(r)
        }
    return result
}
fun skipTarget(positionMs: Long, ranges: List<TimeRange>, enabled: Boolean): Long? =
    if (!enabled) null else ranges.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }?.endMs

data class SubtitleSegment(val range: TimeRange, val text: String)
fun parseVtt(input: String): List<SubtitleSegment> {
    fun time(value: String): Long {
        val p = value.replace(',', '.').split(':')
        require(p.size in 2..3)
        return ((if (p.size == 3) p[0].toLong() * 3600 else 0) * 1000 +
            p[p.size - 2].toLong() * 60000 + (p.last().toDouble() * 1000).toLong())
    }
    return input.replace("\r\n", "\n").split(Regex("\n\\s*\n")).mapNotNull { block ->
        val lines = block.lines(); val i = lines.indexOfFirst { " --> " in it }
        if (i < 0) null else {
            val ends = lines[i].split(" --> ")
            SubtitleSegment(TimeRange(time(ends[0].trim()), time(ends[1].trim().substringBefore(' '))),
                lines.drop(i + 1).joinToString(" ").replace(Regex("<[^>]*>"), "").trim())
        }
    }
}
