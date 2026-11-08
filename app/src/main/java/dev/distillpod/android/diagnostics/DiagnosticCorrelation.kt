package dev.distillpod.android.diagnostics

import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * Version 1 content-free causal identity. Null means unobserved, never false or failure.
 * IDs are independently generated UUIDs, never content hashes or account identifiers.
 * A generation attempt may have multiple executions (for example review-only resume).
 */
data class DiagnosticCorrelation(
    val captureRequestId: String? = null,
    val momentId: String? = null,
    val generationAttemptId: String? = null,
    val executionId: String? = null,
    val transcriptRevisionId: String? = null,
    val previousAttemptId: String? = null,
    val retryTrigger: String = "unknown",
    val contextExpanded: Boolean? = null,
    val transcriptChanged: Boolean? = null,
    val modelChanged: Boolean? = null,
    val promptChanged: Boolean? = null,
    val candidateReused: Boolean? = null
) {
    init {
        ids().forEach { it?.let { value ->
            require(UUID.fromString(value).toString() == value && UUID.fromString(value).version() == 4) {
                "Correlation identifiers must be canonical random UUIDs"
            }
        } }
        require(retryTrigger in RETRY_TRIGGERS)
    }
    private fun ids() = listOf(captureRequestId,momentId,generationAttemptId,executionId,transcriptRevisionId,previousAttemptId)
    private fun flags() = listOf(contextExpanded,transcriptChanged,modelChanged,promptChanged,candidateReused)
    fun fields(): Map<String,Any> = buildMap {
        put("correlation_version",1)
        // Complete means the capture-to-generation identity chain is present, not that every optional
        // comparison is known. Missing flags must still be treated as unknown by consumers.
        put("correlation_complete",ids().take(5).all { it != null })
        listOf("capture_request_id","moment_id","generation_attempt_id","execution_id","transcript_revision_id","previous_attempt_id")
            .zip(ids()).forEach { (key,value) -> if(value!=null)put(key,value) }
        put("retry_trigger",retryTrigger)
        listOf("context_expanded","transcript_changed","model_changed","prompt_changed","candidate_reused")
            .zip(flags()).forEach { (key,value) -> if(value!=null)put(key,value) }
    }
    internal fun write(out:DataOutputStream) {
        ids().forEach { out.writeUTF(it.orEmpty()) }
        out.writeUTF(retryTrigger)
        flags().forEach { out.writeByte(when(it) { null -> -1; false -> 0; true -> 1 }) }
    }
    companion object {
        val RETRY_TRIGGERS=setOf("initial","explicit_retry","expanded_context","transcript_changed","review_resume","process_recovery","unknown")
        internal fun read(input:DataInputStream):DiagnosticCorrelation {
            val ids=List(6) { input.readUTF().ifEmpty { null } }
            val trigger=input.readUTF()
            val flags=List(5) { when(val value=input.readByte().toInt()) { -1 -> null; 0 -> false; 1 -> true; else -> error("Invalid correlation flag: $value") } }
            return DiagnosticCorrelation(ids[0],ids[1],ids[2],ids[3],ids[4],ids[5],trigger,flags[0],flags[1],flags[2],flags[3],flags[4])
        }
    }
}
