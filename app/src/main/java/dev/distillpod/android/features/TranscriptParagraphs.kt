package dev.distillpod.android.features

import dev.distillpod.android.data.SegmentEntity

/** A pause longer than this between segments starts a new reading paragraph (proposal 5.3). */
internal const val PARAGRAPH_GAP_MS = 1_200L

/** Paragraphs stop growing at about this many characters so the reader keeps a steady rhythm. */
internal const val PARAGRAPH_MAX_CHARS = 320

/**
 * Consecutive transcript segments read as one paragraph. Each segment keeps its own timing, so seeking,
 * the #179 focus and saved-moment highlights still use the original segment times.
 * [firstSegment] is the index of the first segment in the transcript's full segment list.
 */
internal data class TranscriptParagraph(val firstSegment: Int, val segments: List<SegmentEntity>) {
    val startMs: Long get() = segments.first().startMs
    val endMs: Long get() = segments.maxOf { it.endMs }
    val text: String = segments.joinToString(" ") { it.text.trim() }
    val lastSegment: Int get() = firstSegment + segments.size - 1
    val key: String get() = "${segments.first().startMs}:${segments.first().ordinal}"
}

/**
 * Groups ordered segments into reading paragraphs. A new paragraph starts when the silence before a
 * segment exceeds [gapMs], when the speaker changes (only if [speaker] knows the speakers), or when adding
 * the segment would take the paragraph past [maxChars]. A single long segment is never split.
 */
internal fun transcriptParagraphs(segments: List<SegmentEntity>, speaker: (SegmentEntity) -> String? = { null },
    gapMs: Long = PARAGRAPH_GAP_MS, maxChars: Int = PARAGRAPH_MAX_CHARS): List<TranscriptParagraph> {
    val paragraphs = ArrayList<TranscriptParagraph>()
    var start = 0
    var length = 0
    segments.forEachIndexed { index, segment ->
        val text = segment.text.trim().length
        if (index > start) {
            val previous = segments[index - 1]
            val pause = segment.startMs - previous.endMs > gapMs
            val turn = speaker(segment) != speaker(previous)
            val full = length + 1 + text > maxChars
            if (pause || turn || full) {
                paragraphs += TranscriptParagraph(start, segments.subList(start, index).toList())
                start = index
                length = 0
            }
        }
        length += if (index == start) text else 1 + text
    }
    if (start < segments.size) paragraphs += TranscriptParagraph(start, segments.subList(start, segments.size).toList())
    return paragraphs
}

/** The paragraph holding the segment at [segmentIndex], or -1 when there is none. */
internal fun paragraphOfSegment(paragraphs: List<TranscriptParagraph>, segmentIndex: Int): Int =
    if (segmentIndex < 0) -1 else paragraphs.indexOfFirst { segmentIndex in it.firstSegment..it.lastSegment }

/**
 * The paragraph being heard at [positionMs]: the last one that has started, until the next one starts or,
 * for the final paragraph, until its last segment ends. -1 before the first paragraph or after the end.
 */
internal fun currentParagraphIndex(paragraphs: List<TranscriptParagraph>, positionMs: Long): Int {
    if (paragraphs.isEmpty() || positionMs < paragraphs.first().startMs) return -1
    var low = 0
    var high = paragraphs.lastIndex
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (paragraphs[mid].startMs <= positionMs) low = mid else high = mid - 1
    }
    return if (low == paragraphs.lastIndex && positionMs >= paragraphs[low].endMs) -1 else low
}

/** Indices of the paragraphs whose text contains [query], ignoring case; empty for a blank query. */
internal fun transcriptMatches(paragraphs: List<TranscriptParagraph>, query: String): List<Int> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    return paragraphs.indices.filter { paragraphs[it].text.contains(needle, ignoreCase = true) }
}

/** A saved moment overlapping [segment]'s time range, if any; [ranges] are (id, startMs, endMs). */
internal fun momentForSegment(segment: SegmentEntity, ranges: List<Triple<String, Long, Long>>): String? =
    ranges.firstOrNull { (_, start, end) -> segment.startMs < end && start < segment.endMs }?.first
