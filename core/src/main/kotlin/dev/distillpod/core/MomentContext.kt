package dev.distillpod.core

import java.text.BreakIterator
import java.util.Locale

/** Sentence candidates keep their original spelling and enclosing segment times. */
data class MomentSpan(val id: Int, val segment: Int, val startMs: Long, val endMs: Long, val text: String)
data class MomentPassage(val start: Int, val end: Int, val text: String, val startMs: Long, val endMs: Long)
data class MomentContext(val capture: TimeRange, val range: TimeRange, val spans: List<MomentSpan>) {
    fun passage(first: Int, last: Int): MomentPassage {
        require(first >= 0 && last >= first && last < spans.size) { "Invalid source references" }
        val chosen = spans.subList(first, last + 1)
        val start = chosen.minOf { it.startMs }; val end = chosen.maxOf { it.endMs }
        require(end - start <= 90_000) { "Choose a focused thought of at most 90 seconds" }
        require(start < capture.endMs && end > capture.startMs) { "The selected idea is outside the captured moment" }
        return MomentPassage(first, last, chosen.joinToString(" ") { it.text }, start, end)
    }

    fun quote(passage: MomentPassage, first: Int, last: Int): String {
        require(first in passage.start..passage.end && last in first..passage.end) { "Quote is outside the selected passage" }
        return spans.subList(first, last + 1).joinToString(" ") { it.text }.also {
            require(it.length in 3..1600) { "Quote is too long; omit it instead of clipping its meaning" }
        }
    }
}

object MomentContexts {
    const val MAX_TEXT = 24_000
    fun range(captureEnd: Long, duration: Long, expanded: Boolean = false): TimeRange {
        require(captureEnd >= 0 && duration >= 0)
        val end = if (duration > 0) minOf(captureEnd + 15_000, duration) else captureEnd + 15_000
        return TimeRange((captureEnd - if (expanded) 180_000 else 120_000).coerceAtLeast(0).coerceAtMost(end), end)
    }
    fun build(capture: TimeRange, range: TimeRange, segments: List<SubtitleSegment>): MomentContext {
        val spans = mutableListOf<MomentSpan>()
        segments.sortedBy { it.range.startMs }.forEachIndexed { index, segment ->
            // Never silently send speech outside the bounded context. Boundary fragments are
            // deliberately not repaired: the generator/reviewer must recognize incompleteness.
            if (segment.range.startMs < range.startMs || segment.range.endMs > range.endMs) return@forEachIndexed
            val iterator = BreakIterator.getSentenceInstance(Locale.ROOT).apply { setText(segment.text) }
            var start = iterator.first()
            var end = iterator.next()
            while (end != BreakIterator.DONE) {
                val text = segment.text.substring(start, end).trim()
                if (text.isNotEmpty()) spans.add(MomentSpan(spans.size, index, segment.range.startMs, segment.range.endMs, text))
                start = end; end = iterator.next()
            }
        }
        require(spans.sumOf { it.text.length + 1 } <= MAX_TEXT) { "This context is too large to distill safely" }
        return MomentContext(capture, range, spans)
    }
}
