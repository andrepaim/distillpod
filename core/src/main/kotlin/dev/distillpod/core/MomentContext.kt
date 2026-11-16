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

/**
 * How much transcript around the tap a distill may read. Expansion reaches in one direction at a
 * time (#124): EARLIER resolves a thought that began before the standard window, LATER finishes a
 * thought that continues after the tap. Every window stays within [MomentContexts.MAX_WINDOW_MS].
 */
enum class ContextWindow(val key: String, val backwardMs: Long, val forwardMs: Long) {
    STANDARD("standard", 120_000, 15_000),
    EARLIER("earlier", 180_000, 15_000),
    LATER("later", 120_000, 75_000);
    companion object {
        /** Attempts written before #124 only record whether context was (backward) expanded. */
        fun of(key: String?, legacyExpanded: Boolean): ContextWindow =
            values().firstOrNull { it.key == key } ?: if (legacyExpanded) EARLIER else STANDARD
    }
}

object MomentContexts {
    const val MAX_TEXT = 24_000
    const val MAX_BACKWARD_MS = 180_000L
    const val MAX_FORWARD_MS = 75_000L
    /** The longest frozen context, as already enforced for stored attempts. */
    const val MAX_WINDOW_MS = 195_000L
    fun range(captureEnd: Long, duration: Long, expanded: Boolean = false): TimeRange =
        range(captureEnd, duration, if (expanded) ContextWindow.EARLIER else ContextWindow.STANDARD)
    fun range(captureEnd: Long, duration: Long, window: ContextWindow): TimeRange {
        require(captureEnd >= 0 && duration >= 0)
        val end = if (duration > 0) minOf(captureEnd + window.forwardMs, duration) else captureEnd + window.forwardMs
        return TimeRange((captureEnd - window.backwardMs).coerceAtLeast(0).coerceAtMost(end), end)
    }
    fun build(capture: TimeRange, range: TimeRange, segments: List<SubtitleSegment>,
        measured: ((TimeRange, List<SubtitleSegment>, List<MomentSpan>) -> Unit)? = null): MomentContext {
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
        measured?.invoke(range, segments, spans)
        require(spans.sumOf { it.text.length + 1 } <= MAX_TEXT) { "This context is too large to distill safely" }
        return MomentContext(capture, range, spans)
    }
}
