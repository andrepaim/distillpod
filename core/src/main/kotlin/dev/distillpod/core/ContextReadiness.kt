package dev.distillpod.core

/** Numeric evidence only. Coverage measures source intervals, never semantic quality. */
class ContextReadiness {
    var measured=false; private set
    var inputSegments=0; private set
    var excludedLeft=0; private set
    var excludedRight=0; private set
    var usableSegments=0; private set
    var usableSpans=0; private set
    var contextChars=0L; private set
    val oversized get()=contextChars>MomentContexts.MAX_TEXT
    fun built(range:TimeRange, segments:List<SubtitleSegment>, spans:List<MomentSpan>) {
        measured=true
        inputSegments=segments.size
        excludedLeft=segments.count {it.range.startMs<range.startMs}
        excludedRight=segments.count {it.range.endMs>range.endMs}
        usableSegments=spans.map {it.segment}.distinct().size
        usableSpans=spans.size
        contextChars=spans.sumOf {it.text.length.toLong()+1}
    }
    companion object {
        fun missing(range:TimeRange,coverage:List<TimeRange>):Long {
            val covered=normalizeRanges(coverage,Long.MAX_VALUE).sumOf {
                (minOf(it.endMs,range.endMs)-maxOf(it.startMs,range.startMs)).coerceAtLeast(0)
            }
            return (range.endMs-range.startMs-covered).coerceAtLeast(0)
        }
    }
}
