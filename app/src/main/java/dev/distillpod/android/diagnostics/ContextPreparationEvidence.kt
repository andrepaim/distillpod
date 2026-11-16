package dev.distillpod.android.diagnostics

import dev.distillpod.core.ContextReadiness

/** One bounded snapshot per preparation. Never accepts content-bearing payload fields. */
internal class ContextPreparationEvidence {
    var source="missing"
    var outcome="failed"
    var reason="internal"
    var window=dev.distillpod.core.ContextWindow.STANDARD
    val expanded get()=window!=dev.distillpod.core.ContextWindow.STANDARD
    var durationKnown=false
    var coverageComplete=false
    var missingDuration:Long?=null
    var frozen=false
    var waitInput:ContextWaitInput?=null
    val counts=ContextReadiness()
    fun fields(elapsed:Long):Map<String,Any> = buildMap {
        put("context_schema_version",1)
        put("transcript_source",source.takeIf {it in setOf("publisher","whisper","online","missing")} ?: "other")
        put("outcome",outcome.takeIf {it in OUTCOMES} ?: "failed")
        put("context_reason",reason.takeIf {it in REASONS} ?: "internal")
        put("expanded_context",expanded)
        put("backward_window_ms",window.backwardMs)
        put("forward_window_ms",window.forwardMs)
        put("duration_known",durationKnown)
        put("coverage_measured",missingDuration!=null)
        put("coverage_complete",coverageComplete)
        missingDuration?.let {put("missing_duration_ms",it.coerceAtLeast(0))}
        put("segments_measured",counts.measured)
        put("input_segment_count",counts.inputSegments)
        put("usable_segment_count",counts.usableSegments)
        put("usable_span_count",counts.usableSpans)
        put("context_text_units",counts.contextChars)
        put("excluded_left_count",counts.excludedLeft)
        put("excluded_right_count",counts.excludedRight)
        put("frozen_context",frozen)
        put("preparation_elapsed_ms",elapsed.coerceAtLeast(0))
    }
    companion object {
        val OUTCOMES=setOf("queued","waiting_auth","transcript_required","no_speech","needs_context","review_source","unchanged_context","superseded","episode_missing","cancelled","failed")
        val REASONS=setOf("ready","coverage_missing","source_mismatch","revision_mismatch","empty_speech","empty_context","oversized","invalid_bounds","unchanged_context","episode_missing","superseded","cancelled","internal")
    }
}
