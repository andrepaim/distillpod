package dev.distillpod.android.data

import dev.distillpod.android.diagnostics.DiagnosticCorrelation
import org.json.JSONObject
import java.util.UUID

/** Local provenance only. Never pass this metadata to the AI provider or portable library. */
internal object AttemptDiagnostics {
    fun context(expanded:Boolean, request:String?=null, previous:MomentAttemptEntity?=null,
        trigger:String="initial",consentEpoch:String?=null):String {
        if(consentEpoch==null)return JSONObject().put("expanded",expanded).toString()
        val prior=previous?.let { JSONObject(forConsent(it.contextJson,consentEpoch)).optJSONObject("_diagnostics") }
        val metadata=JSONObject().put("version",1).put("consent_epoch",consentEpoch).put("retry_trigger",trigger)
            .put("capture_request_id",request ?: prior?.optString("capture_request_id")?.ifBlank { null })
            .put("previous_attempt_id",previous?.id)
        return JSONObject().put("expanded",expanded).put("_diagnostics",metadata).toString()
    }
    fun forConsent(original:String,epoch:String?):String = JSONObject(original).apply {
        if(epoch==null || optJSONObject("_diagnostics")?.optString("consent_epoch")!=epoch)remove("_diagnostics")
    }.toString()
    fun preserve(original:String,prepared:String,epoch:String?):String = JSONObject(prepared).apply {
        JSONObject(forConsent(original,epoch)).optJSONObject("_diagnostics")?.let { put("_diagnostics",it) }
    }.toString()
    fun resume(original:String,trigger:String,epoch:String?):String = JSONObject(forConsent(original,epoch)).apply {
        if(epoch!=null) {
            val metadata=optJSONObject("_diagnostics") ?: JSONObject().put("version",1).put("consent_epoch",epoch)
            put("_diagnostics",metadata.put("retry_trigger",trigger).put("pending_execution_trigger",trigger))
        }
    }.toString()
    fun executionContext(attempt:MomentAttemptEntity,epoch:String?):String = JSONObject(forConsent(attempt.contextJson,epoch)).apply {
        if(epoch==null)return@apply
        val metadata=optJSONObject("_diagnostics") ?: JSONObject().put("version",1).put("consent_epoch",epoch)
        val pending=metadata.optString("pending_execution_trigger").takeIf {it in DiagnosticCorrelation.RETRY_TRIGGERS}
        val trigger=pending ?: if(attempt.generationCalls>0 || attempt.reviewCalls>0)"process_recovery"
            else metadata.optString("retry_trigger").takeIf {it in DiagnosticCorrelation.RETRY_TRIGGERS} ?: "unknown"
        metadata.remove("pending_execution_trigger")
        put("_diagnostics",metadata.put("retry_trigger",trigger))
    }.toString()
    fun correlation(attempt:MomentAttemptEntity,previous:MomentAttemptEntity?=null,
        revision:String?=null,consentEpoch:String?=null):DiagnosticCorrelation {
        val ctx=JSONObject(forConsent(attempt.contextJson,consentEpoch))
        val metadata=ctx.optJSONObject("_diagnostics")
        val old=previous?.let { JSONObject(it.contextJson) }
        val currentRevision=revision ?: ctx.optString("revision").ifBlank { null }
        val previousRevision=old?.optString("revision")?.ifBlank { null }
        return DiagnosticCorrelation(
            captureRequestId=randomId(metadata?.optString("capture_request_id")),
            momentId=randomId(attempt.momentId),generationAttemptId=randomId(attempt.id),
            executionId=randomId(attempt.run),transcriptRevisionId=randomId(currentRevision),
            previousAttemptId=randomId(metadata?.optString("previous_attempt_id")),
            retryTrigger=metadata?.optString("retry_trigger")?.takeIf {it in DiagnosticCorrelation.RETRY_TRIGGERS} ?: "unknown",
            contextExpanded=if(ctx.has("expanded"))ctx.getBoolean("expanded") else null,
            transcriptChanged=if(previousRevision!=null && currentRevision!=null)previousRevision!=currentRevision else null,
            modelChanged=if(!previous?.model.isNullOrBlank() && attempt.model.isNotBlank())previous!!.model!=attempt.model else null,
            promptChanged=if(old?.has("promptVersion")==true && ctx.has("promptVersion"))old.getInt("promptVersion")!=ctx.getInt("promptVersion") else null,
            candidateReused=attempt.candidateJson.isNotBlank(),collectionEpoch=consentEpoch)
    }
    fun previousId(attempt:MomentAttemptEntity):String? =
        JSONObject(attempt.contextJson).optJSONObject("_diagnostics")?.optString("previous_attempt_id")?.ifBlank {null}
    private fun randomId(value:String?):String? = value?.takeIf {
        runCatching {UUID.fromString(it).let {id->id.version()==4 && id.toString()==it}}.getOrDefault(false)
    }
}
