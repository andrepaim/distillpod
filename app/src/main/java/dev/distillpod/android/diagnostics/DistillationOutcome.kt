package dev.distillpod.android.diagnostics

import dev.distillpod.android.data.MomentGeneration

/** Content-free observations for one execution. Call counts are gateway attempts, never billing. */
internal class DistillationOutcome {
    private var stage="generation"
    private var outcome="stale_result_suppressed"
    private val outcomes=mutableMapOf("generation" to "not_run","review" to "not_run")
    private val models=mutableMapOf("generation" to "not_run","review" to "not_run")
    private val calls=mutableMapOf("generation" to 0,"review" to 0)
    private val elapsed=mutableMapOf("generation" to -1L,"review" to -1L)
    private var started:Long?=null
    private var review:MomentGeneration.Review?=null
    private var quoteOmitted:Boolean?=null
    private var finished=false

    fun examining(stage:String) {if(stage in outcomes)this.stage=stage}
    fun cached(stage:String, model:String) {
        if(stage !in outcomes)return
        outcomes[stage]="cached";models[stage]=model(model)
    }
    fun begin(stage:String) {
        if(stage !in outcomes)return
        this.stage=stage
        calls[stage]=(calls.getValue(stage)+1).coerceAtMost(1000)
        models[stage]="unknown";outcomes[stage]="request_started"
        started=System.nanoTime()
    }
    private fun stop() {
        started?.let {elapsed[stage]=((System.nanoTime()-it)/1_000_000).coerceIn(0,86_400_000)}
        started=null
    }
    fun response(model:String) { stop();models[stage]=model(model) }
    fun generated(value:String) {
        stage="generation"
        val result=when(value) {"ready"->"ready";"needs_context"->"generator_needs_context";"unclear_source"->"generator_unclear_source";else->"invalid_response"}
        outcomes[stage]=result
        if(result!="ready")outcome=result
    }
    fun reviewed(value:MomentGeneration.Review) {
        stage="review";review=value
        outcomes[stage]=if(value.accepted)"accepted" else "review_rejected"
        if(!value.accepted)outcome="review_rejected"
    }
    fun publishing() {stage="publication"}
    fun published(omitted:Boolean) {
        quoteOmitted=omitted
        outcome=if(omitted)"published_without_quote" else "published"
    }
    fun fail(value:String) {
        stop()
        outcome=value.takeIf {it in FAILURES} ?: "internal_failure"
        if(stage in outcomes)outcomes[stage]=outcome
    }
    fun discarded() {stop();outcome="stale_result_suppressed"}
    /** Exactly one terminal envelope for this execution, including returns and exceptions. */
    fun finish():Boolean {if(finished)return false;finished=true;stop();return true}
    fun fields():Map<String,Any> = buildMap {
        put("outcome_version",1);put("stage",stage);put("stage_outcome",outcome)
        put("prompt_version",MomentGeneration.VERSION)
        put("generation_outcome",outcomes.getValue("generation"));put("review_outcome",outcomes.getValue("review"))
        put("generator_model",models.getValue("generation"));put("reviewer_model",models.getValue("review"))
        for(s in listOf("generation","review")) {
            put("${s}_attempted_calls",calls.getValue(s));put("${s}_elapsed_ms",elapsed.getValue(s))
        }
        put("passage_complete",review?.passageComplete ?: "unknown")
        put("takeaway_supported",review?.takeawaySupported ?: "unknown")
        put("relevant",review?.relevant ?: "unknown")
        put("quote_faithful",review?.reportedQuoteFaithful ?: "unknown")
        put("needs_context",review?.needsContext ?: "unknown")
        put("contradictory_review",review?.contradictory ?: "unknown")
        for(kind in listOf("incomplete","unsupported","irrelevant","quote"))
            put("issue_${kind}_count",review?.issueCounts?.get(kind)?.coerceIn(0,20) ?: if(review==null)-1 else 0)
        put("quote_omitted",quoteOmitted ?: "unknown")
    }
    companion object {
        private val FAILURES=setOf("invalid_response","interrupted","cancelled","auth_failure","quota","request_failed","internal_failure")
        // Public identifiers only: arbitrary syntactically plausible provider strings can contain private data.
        private val MODELS=setOf("gpt-4o","gpt-4o-mini","gpt-4.1","gpt-4.1-mini","gpt-4.1-nano",
            "gpt-5","gpt-5-mini","gpt-5-nano","gpt-5.1","gpt-5.2","gpt-5.3","gpt-5.4",
            "gpt-5-codex","gpt-5.1-codex","gpt-5.1-codex-mini","gpt-5.2-codex","gpt-5.3-codex",
            "o1","o1-mini","o3","o3-mini","o3-pro","o4-mini")
        private fun model(value:String)=value.takeIf {it.length<=64 && it in MODELS} ?: "unknown"
    }
}
