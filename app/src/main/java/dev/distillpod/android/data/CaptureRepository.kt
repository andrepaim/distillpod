package dev.distillpod.android.data

import androidx.room.withTransaction
import dev.distillpod.android.QuotaExceeded
import dev.distillpod.android.ReauthorizationRequired
import dev.distillpod.android.ai.AiGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import dev.distillpod.core.*
import dev.distillpod.android.diagnostics.DiagnosticCorrelation
import dev.distillpod.android.diagnostics.DistillationOutcome

fun jsonObjectAnswer(text: String): JSONObject {
    val stripped = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    return JSONObject(stripped)
}
class CaptureRepository(private val db: AppDatabase, private val ai: AiGateway, private val connected: () -> Boolean,
    private val prepare: suspend (String) -> Unit, private val enqueue: (String) -> Unit,
    private val onPortableChanged: () -> Unit = {}, private val onSaved: (String) -> Unit = {},
    private val diagnostics: dev.distillpod.android.diagnostics.Diagnostics? = null) {
    private val dao = db.library()
    private val portable = dev.distillpod.android.sync.PortableLibrarySync(db)
    private val busy = setOf("waiting_transcript", "queued", "running", "reviewing")

    suspend fun capture(id: String, position: Long, verifiedSource: Boolean): String {
        val saved=save(id,position,verifiedSource,source="phone")
        prepareSaved(saved)
        return saved
    }
    /** Durable acceptance is independent from preparation, authorization and inference. */
    suspend fun save(id: String, position: Long, verifiedSource: Boolean, source:String="background"): String {
        val request=UUID.randomUUID().toString()
        val requestEpoch=diagnostics?.correlationEpoch
        val requestEvidence=DiagnosticCorrelation(captureRequestId=if(requestEpoch!=null)request else null,
            collectionEpoch=requestEpoch ?: UUID.randomUUID().toString())
        diagnostics?.event("capture.started",request,source=source,correlation=requestEvidence)
        try {
            require(position>=0) { "Playback position is invalid" }
            val ep = dao.episode(id) ?: error("Episode is unavailable")
            val (saved,created) = db.withTransaction {
                val recent=dao.recentCapture(id,position,System.currentTimeMillis()-2000)
                if(recent!=null)recent to false else {
                    val fresh=DistillationEntity(UUID.randomUUID().toString(),id,(position-60000).coerceAtLeast(0),position,
                        fingerprint=if(verifiedSource)ep.mediaFingerprint else "unverified:${ep.id}",attempt=UUID.randomUUID().toString())
                    dao.putDistillation(fresh)
                    dao.putMomentAttempt(MomentAttemptEntity(fresh.attempt,fresh.id,AttemptDiagnostics.context(false,request=request,consentEpoch=requestEpoch?.takeIf {it==diagnostics?.correlationEpoch})))
                    portable.recordMoment(fresh)
                    fresh to true
                }
            }
            val correlation=dao.momentAttempt(saved.attempt)?.let { correlation(it) }?.copy(captureRequestId=requestEvidence.captureRequestId,collectionEpoch=requestEvidence.collectionEpoch)
                ?: requestEvidence.copy(momentId=saved.id)
            diagnostics?.event("capture.completed",request,source=source,related=saved.id,correlation=correlation)
            diagnostics?.event("capture.saved",saved.id,reason=if(created) "created" else "deduplicated",source=source,correlation=correlation)
            onPortableChanged()
            if(created)try {onSaved(saved.id)}catch(_:Exception) { /* Optional feedback cannot fail a durable save. */ }
            return saved.id
        } catch(error:Exception) {
            diagnostics?.event("capture.failed",request,reason=dev.distillpod.android.diagnostics.Diagnostics.reason(error),source=source,correlation=requestEvidence)
            throw error
        }
    }

    suspend fun prepareSaved(id:String) {
        dao.distillation(id)?.takeIf {it.state=="waiting_transcript"}?.let {hydrate(it)}
    }
    suspend fun resumeSaved() {
        dao.distillations().filter {it.state=="waiting_transcript"}.forEach {
            try {prepareSaved(it.id)} catch(cancelled:CancellationException) {throw cancelled} catch(error:Exception) { diagnostics?.failure("capture",error,it.id) }
        }
    }
    internal suspend fun correlation(attempt:MomentAttemptEntity,revision:String?=null):DiagnosticCorrelation {
        val epoch=diagnostics?.correlationEpoch
        val local=attempt.copy(contextJson=AttemptDiagnostics.forConsent(attempt.contextJson,epoch))
        return AttemptDiagnostics.correlation(local,AttemptDiagnostics.previousId(local)?.let {dao.momentAttempt(it)},revision,epoch)
            .copy(collectionEpoch=epoch ?: UUID.randomUUID().toString())
    }

    private suspend fun hydrate(saved: DistillationEntity) {
        val started=System.nanoTime()
        val evidence=dev.distillpod.android.diagnostics.ContextPreparationEvidence()
        val collectionEpoch=diagnostics?.correlationEpoch ?: UUID.randomUUID().toString()
        var identity:DiagnosticCorrelation?=DiagnosticCorrelation(collectionEpoch=collectionEpoch)
        var invalidBoundsFailure=false
        try {
            val ep = dao.episode(saved.episodeId) ?: run {
                evidence.outcome="episode_missing";evidence.reason="episode_missing";return
            }
            val attempt = dao.momentAttempt(saved.attempt) ?: MomentAttemptEntity(UUID.randomUUID().toString(),saved.id,"{\"expanded\":false}")
            identity=correlation(attempt).copy(collectionEpoch=collectionEpoch)
            val expanded = JSONObject(attempt.contextJson).optBoolean("expanded")
            evidence.expanded=expanded
            val availableTranscript=dao.transcript(ep.id)
            evidence.source=availableTranscript?.source ?: "missing"
            val transcript = availableTranscript?.takeUnless { it.source == "online" }
            identity=correlation(attempt,transcript?.revision).copy(collectionEpoch=collectionEpoch)
            // Publisher transcripts can be complete even when the feed has no episode duration.
            // Only a complete, matching transcript can supply the missing end boundary.
            val duration = ep.durationMs.takeIf { it > 0 }
                ?: transcript?.takeIf { it.state == "ready" && it.fingerprint == ep.mediaFingerprint && it.durationMs > 0 }?.durationMs
                ?: 0L
            evidence.durationKnown=duration>0
            val range = try {MomentContexts.range(saved.endMs, duration, expanded)}
                catch(error:IllegalArgumentException) {invalidBoundsFailure=true;throw error}
            val storedCoverage=transcript?.let {dao.coverage(ep.id,it.revision)}.orEmpty()
            val coverage = try {storedCoverage.map {c -> TimeRange(c.startMs,c.endMs)}}
                catch(error:IllegalArgumentException) {invalidBoundsFailure=true;throw error}
            val covered = transcript != null && transcript.fingerprint == ep.mediaFingerprint && normalizeRanges(coverage,Long.MAX_VALUE)
                .any { it.startMs <= range.startMs && it.endMs >= range.endMs }
            evidence.coverageComplete=covered
            evidence.missingDuration=ContextReadiness.missing(range,coverage)
            if (!covered) {
                evidence.outcome="transcript_required"
                evidence.reason=if(transcript!=null && transcript.fingerprint!=ep.mediaFingerprint)"source_mismatch" else "coverage_missing"
                diagnostics?.event("capture.waiting",saved.id,reason="transcript_required",related=transcript?.revision,
                    attempt=saved.attempt,correlation=correlation(attempt,transcript?.revision))
                prepare(ep.id)
                // Scheduling may create or replace the revision after the original save.
                dao.transcript(ep.id)?.let { dependency ->
                    diagnostics?.event("capture.transcript_link",saved.id,reason="transcript_required",related=dependency.revision,
                        correlation=correlation(attempt,dependency.revision))
                }
                return
            }
            val segments = dao.window(ep.id,transcript!!.revision,range.startMs,range.endMs)
            val context = try { MomentContexts.build(TimeRange(saved.startMs,saved.endMs),range,
                segments.map { SubtitleSegment(TimeRange(it.startMs,it.endMs),it.text) }, evidence.counts::built) }
            catch (_: IllegalArgumentException) {
                evidence.outcome="needs_context"
                evidence.reason=if(evidence.counts.oversized)"oversized" else "invalid_bounds"
                val changed=db.withTransaction { dao.distillation(saved.id)?.takeIf { it.attempt==saved.attempt && it.state=="waiting_transcript" }?.let {
                    dao.putDistillation(it.copy(state="needs_context",error="This source needs a shorter, clearer passage. Your moment is saved."))
                    true
                } ?: false }
                if(!changed) {evidence.outcome="superseded";evidence.reason="superseded"}
                return
            }
            val raw = dao.window(ep.id,transcript.revision,saved.startMs,saved.endMs).joinToString(" ") { it.text.trim() }.trim()
            val original = if(saved.sourceJson.isNotBlank()) JSONObject(saved.sourceJson).getString("originalExcerpt") else saved.excerpt.ifBlank { raw }
            val state = when {
                raw.isBlank() -> "no_speech"
                context.spans.isEmpty() -> "needs_context"
                saved.fingerprint != ep.mediaFingerprint -> "review_source"
                !connected() -> "waiting_auth"
                else -> "queued"
            }
            evidence.outcome=state
            evidence.reason=when(state) {
                "no_speech" -> "empty_speech"
                "needs_context" -> "empty_context"
                "review_source" -> "source_mismatch"
                else -> "ready"
            }
            val changed = db.withTransaction {
                val current=dao.distillation(saved.id)
                if(current?.state!="waiting_transcript" || current.attempt!=saved.attempt) false else {
                    // Observe stale dependencies without changing selection or existing persistence policy.
                    if(dao.episode(ep.id)?.mediaFingerprint!=ep.mediaFingerprint)evidence.reason="source_mismatch"
                    else if(dao.transcript(ep.id)?.revision!=transcript.revision)evidence.reason="revision_mismatch"
                    dao.putMomentAttempt(attempt.copy(contextJson=AttemptDiagnostics.preserve(attempt.contextJson,MomentGeneration.contextJson(context,transcript.revision,ep.mediaFingerprint,original,expanded),diagnostics?.correlationEpoch)))
                    val updated=current.copy(excerpt=current.excerpt.ifBlank { raw },attempt=attempt.id,
                        revision=if(current.insight==null)transcript.revision else current.revision,state=state,
                        error=when(state) { "no_speech" -> "No transcribed speech was found in this window."; "needs_context" -> "No complete source segments fit this window. Expand the context."; else -> null })
                    dao.putDistillation(updated);if(updated.insight==null)portable.recordMoment(updated);true
                }
            }
            evidence.frozen=changed
            if(!changed) {evidence.outcome="superseded";evidence.reason="superseded"}
            if(changed) {
                val prepared=dao.momentAttempt(attempt.id) ?: attempt
                identity=correlation(prepared).copy(collectionEpoch=collectionEpoch)
                diagnostics?.event("capture.transcript_link",saved.id,reason="ready",related=transcript.revision,correlation=correlation(prepared))
                diagnostics?.event("capture.prepared",saved.id,reason=state,correlation=correlation(prepared));onPortableChanged()
            }
            if(changed && state=="queued")enqueue(saved.id)
        } catch(cancelled:CancellationException) {
            evidence.outcome="cancelled";evidence.reason="cancelled";throw cancelled
        } catch(error:Exception) {
            evidence.outcome="failed"
            evidence.reason=if(invalidBoundsFailure)"invalid_bounds" else "internal"
            throw error
        } finally {
            diagnostics?.contextSummary(saved.id,evidence,(System.nanoTime()-started)/1_000_000,identity)
        }
    }

    suspend fun transcriptUpdated(id: String) {
        dao.distillations().filter { it.episodeId==id && it.state=="waiting_transcript" }.forEach { hydrate(it) }
    }

    /** Explicit regeneration keeps the published result until a replacement has passed review. */
    suspend fun improve(id: String, expanded: Boolean = false, retryTrigger:String=if(expanded)"expanded_context" else "explicit_retry") {
        val pending=db.withTransaction {
            val current=dao.distillation(id) ?: return@withTransaction null
            if(current.state in busy)return@withTransaction null
            val attempt=MomentAttemptEntity(UUID.randomUUID().toString(),id,AttemptDiagnostics.context(expanded,previous=dao.momentAttempt(current.attempt),trigger=retryTrigger,consentEpoch=diagnostics?.correlationEpoch))
            dao.putMomentAttempt(attempt)
            current.copy(attempt=attempt.id,state="waiting_transcript",error=null).also { dao.putDistillation(it) }
        } ?: return
        hydrate(pending)
    }

    suspend fun generate(id: String, confirmSource: Boolean = false) {
        val saved=dao.distillation(id) ?: return
        if(saved.state in busy || saved.state=="succeeded")return
        val attempt=dao.momentAttempt(saved.attempt)
        if(attempt==null || !JSONObject(attempt.contextJson).has("spans")) { improve(id);return }
        val ep=dao.episode(saved.episodeId) ?: return
        val ctx=JSONObject(attempt.contextJson)
        if(ctx.getString("fingerprint")!=ep.mediaFingerprint || dao.transcript(ep.id)?.revision!=ctx.getString("revision")) {
            improve(id,ctx.optBoolean("expanded"),"transcript_changed");return
        }
        if(saved.fingerprint!=ep.mediaFingerprint && !confirmSource || saved.state=="review_source" && !confirmSource) {
            dao.putDistillation(saved.copy(state="review_source",error="Review this source before generating."));return
        }
        // Content rejections are new attempts. Failed review requests resume the saved candidate.
        if(attempt.state in setOf("needs_context","unclear_source","rejected","succeeded")) { improve(id,ctx.optBoolean("expanded"));return }
        val queued=db.withTransaction {
            val current=dao.distillation(id) ?: return@withTransaction false
            if(current.attempt!=saved.attempt || current.state in busy)return@withTransaction false
            dao.putMomentAttempt(attempt.copy(run=UUID.randomUUID().toString(),contextJson=AttemptDiagnostics.resume(attempt.contextJson,if(attempt.candidateJson.isNotBlank())"review_resume" else "explicit_retry",diagnostics?.correlationEpoch)))
            dao.putDistillation(current.copy(state=if(connected())"queued" else "waiting_auth",fingerprint=if(current.insight==null)ep.mediaFingerprint else current.fingerprint,error=null));true
        }
        if(queued)diagnostics?.event("distillation.queued",id,reason=if(connected())"queued" else "waiting_auth",correlation=dao.momentAttempt(saved.attempt)?.let {correlation(it)})
        if(queued && connected())enqueue(id)
    }

    suspend fun cancel(id: String) {
        var cancelled:DiagnosticCorrelation?=null
        val changed=db.withTransaction {
            val current=dao.distillation(id)?.takeIf {it.state in busy} ?: return@withTransaction false
            dao.momentAttempt(current.attempt)?.let {cancelled=correlation(it);dao.putMomentAttempt(it.copy(run=UUID.randomUUID().toString()))}
            dao.putDistillation(current.copy(state="failed",error="Generation paused. Your saved result and source are kept."))
            true
        }
        if(changed)diagnostics?.event("distillation.cancelled",id,reason="cancelled",correlation=cancelled)
    }

    suspend fun restoreVersion(id: String, versionId: String) {
        db.withTransaction {
            val current=dao.distillation(id) ?: return@withTransaction
            require(current.state !in busy) { "Pause generation before restoring a version" }
            val version=dao.momentVersions(id).firstOrNull { it.id==versionId } ?: return@withTransaction
            archive(current)
            val restored=restoreMomentSnapshot(current,version.snapshot)
            dao.putDistillation(restored);portable.recordMoment(restored)
        };onPortableChanged()
    }
    private suspend fun archive(saved: DistillationEntity) {
        if(!saved.insight.isNullOrBlank())dao.putMomentVersion(MomentVersionEntity(UUID.randomUUID().toString(),saved.id,momentSnapshot(saved)))
    }

    private class ObsoleteMomentSource:IllegalStateException("The transcript or audio changed. Prepare the source again.")

    private suspend fun valid(id: String, attempt: MomentAttemptEntity): DistillationEntity? {
        val current=dao.distillation(id) ?: return null
        if(current.attempt!=attempt.id || current.state !in setOf("running","reviewing") || dao.momentAttempt(attempt.id)?.run!=attempt.run)return null
        val ctx=JSONObject(attempt.contextJson)
        if(dao.episode(current.episodeId)?.mediaFingerprint!=ctx.getString("fingerprint") ||
            dao.transcript(current.episodeId)?.revision!=ctx.getString("revision"))throw ObsoleteMomentSource()
        return current
    }

    suspend fun run(id: String) {
        val (saved,acquiredAttempt,claimedEvidence)=db.withTransaction {
            val current=dao.distillation(id)?.takeIf {it.state=="queued"} ?: return@withTransaction null
            val acquired=dao.momentAttempt(current.attempt)?.let { attempt ->
                attempt.copy(run=UUID.randomUUID().toString(),contextJson=AttemptDiagnostics.executionContext(attempt,diagnostics?.correlationEpoch)).also {dao.putMomentAttempt(it)}
            }
            dao.putDistillation(current.copy(state="running",error=null))
            val observed=runCatching {acquired?.let {correlation(it)}}.getOrNull()
            // Observe the attempted claim before releasing serialization. A suspended caller must
            // not publish an old start after a later cancel/resume. No provider work occurs here.
            runCatching {diagnostics?.event("distillation.started",current.id,attempt=acquired?.run,correlation=observed)}
            Triple(current,acquired,observed)
        } ?: return
        var execution: String? = null
        val evidence:DiagnosticCorrelation?=claimedEvidence
        val summary=DistillationOutcome()
        var parsingResponse=false
        var awaitingResponse=false
        var terminalRecorded=false
        try {
            var attempt=acquiredAttempt ?: error("Prepare the source again before generating")
            execution=attempt.run
            if(attempt.candidateJson.isNotBlank())summary.cached("generation",attempt.model)
            if(attempt.reviewJson.isNotBlank())summary.cached("review",attempt.reviewModel)
            val context=MomentGeneration.context(attempt.contextJson)
            check(context.spans.isNotEmpty()) { "No usable source context" }
            if(valid(id,attempt)==null)return
            if(attempt.candidateJson.isBlank()) {
                val counted=attempt.copy(generationCalls=attempt.generationCalls+1,state="generating")
                if(!db.withTransaction {if(valid(id,attempt)==null)false else {dao.putMomentAttempt(counted);true}})return
                attempt=counted
                val started=System.nanoTime()
                val input=MomentGeneration.input(attempt.contextJson)
                summary.begin("generation")
                awaitingResponse=true
                val answer=ai.complete(MomentGeneration.generatePrompt,input)
                awaitingResponse=false
                summary.response(answer.model)
                parsingResponse=true
                val candidate=MomentGeneration.candidate(answer.text,context)
                parsingResponse=false
                summary.generated(candidate.outcome)
                val next=attempt.copy(candidateJson=answer.text,model=answer.model,generationMs=(System.nanoTime()-started)/1_000_000,
                    state=if(candidate.outcome=="ready")"reviewing" else candidate.outcome)
                val accepted=db.withTransaction {
                    val current=valid(id,attempt) ?: return@withTransaction false
                    dao.putMomentAttempt(next)
                    dao.putDistillation(current.copy(state=if(candidate.outcome=="ready")"reviewing" else candidate.outcome,
                        error=when(candidate.outcome) { "needs_context" -> "This passage needs more context to convey a complete idea.";
                            "unclear_source" -> "The transcript is too unclear to explain faithfully. Review or transcribe it again.";else -> null }))
                    true
                }
                if(!accepted) {diagnostics?.event("distillation.discarded",saved.id,reason="superseded",correlation=evidence);return}
                if(candidate.outcome!="ready") { terminalRecorded=true;diagnostics?.event("distillation.finished",saved.id,reason=candidate.outcome,correlation=evidence?.copy(modelChanged=correlation(next).modelChanged));return }
                attempt=next
            }
            parsingResponse=true
            val candidate=MomentGeneration.candidate(attempt.candidateJson,context)
            parsingResponse=false
            summary.examining("review")
            if(valid(id,attempt)==null)return
            if(attempt.reviewJson.isBlank()) {
                val changed=db.withTransaction { val current=valid(id,attempt) ?: return@withTransaction false
                    dao.putDistillation(current.copy(state="reviewing"));true }
                if(!changed)return
                val counted=attempt.copy(reviewCalls=attempt.reviewCalls+1,state="reviewing")
                if(!db.withTransaction {if(valid(id,attempt)==null)false else {dao.putMomentAttempt(counted);true}})return
                attempt=counted
                val started=System.nanoTime()
                val input=JSONObject().put("source",JSONObject(MomentGeneration.input(attempt.contextJson))).put("candidate",JSONObject(attempt.candidateJson))
                    .put("selectedPassage",candidate.passage!!.text).put("takeaway",candidate.takeaway).put("quote",candidate.quote ?: JSONObject.NULL)
                val reviewInput=input.toString()
                summary.begin("review")
                awaitingResponse=true
                val answer=ai.complete(MomentGeneration.reviewPrompt,reviewInput)
                awaitingResponse=false
                summary.response(answer.model)
                parsingResponse=true
                summary.reviewed(MomentGeneration.review(answer.text,context))
                parsingResponse=false
                val next=attempt.copy(reviewJson=answer.text,reviewModel=answer.model,reviewMs=(System.nanoTime()-started)/1_000_000)
                if(!db.withTransaction { if(valid(id,attempt)==null)false else {dao.putMomentAttempt(next);true} })return
                attempt=next
            }
            parsingResponse=true
            val review=MomentGeneration.review(attempt.reviewJson,context)
            parsingResponse=false
            summary.reviewed(review)
            if(review.accepted)summary.publishing()
            val outcome=db.withTransaction {
                val current=valid(id,attempt) ?: return@withTransaction null
                if(!review.accepted) {
                    val state=if(review.needsContext)"needs_context" else "unclear_source"
                    dao.putMomentAttempt(attempt.copy(state="rejected"))
                    dao.putDistillation(current.copy(state=state,error="The proposed takeaway did not pass source review. Your previous result and source are kept."))
                } else {
                    archive(current)
                    val accepted=candidate.copy(quote=if(review.quoteFaithful)candidate.quote else null)
                    val ctx=JSONObject(attempt.contextJson)
                    val finished=current.copy(excerpt=accepted.passage!!.text,quote=accepted.quote,insight=accepted.takeaway,model=attempt.model,
                        revision=ctx.getString("revision"),fingerprint=ctx.getString("fingerprint"),sourceJson=MomentGeneration.source(attempt,accepted),state="succeeded",error=null)
                    MomentGeneration.validateSource(finished.sourceJson,finished.startMs,finished.endMs)
                    dao.putDistillation(finished);dao.putMomentAttempt(attempt.copy(state="succeeded"));portable.recordMoment(finished)
                }
                if(review.accepted)"succeeded" else if(review.needsContext)"needs_context" else "unclear_source"
            }
            if(outcome==null) {diagnostics?.event("distillation.discarded",saved.id,reason="superseded",correlation=evidence);return}
            terminalRecorded=true
            if(review.accepted)summary.published(candidate.quote==null || !review.quoteFaithful)
            diagnostics?.event("distillation.finished",saved.id,reason=outcome,correlation=evidence?.copy(modelChanged=correlation(attempt).modelChanged))
            onPortableChanged()
        } catch(error: Exception) {
            val alreadyTerminal=terminalRecorded
            // Preserve observed failure even if the ownership-storage check itself fails.
            if(!terminalRecorded) {
                if(error is ObsoleteMomentSource)summary.discarded() else summary.fail(when {
                    error is CancellationException -> "interrupted"
                    error is QuotaExceeded -> "quota"
                    error is ReauthorizationRequired -> "auth_failure"
                    parsingResponse -> "invalid_response"
                    awaitingResponse -> "request_failed"
                    else -> "internal_failure"
                })
                terminalRecorded=true
            }
            val owned=withContext(NonCancellable) { db.withTransaction {
                val current=dao.distillation(id)
                if(current==null || current.attempt!=saved.attempt || current.state !in setOf("running","reviewing") || dao.momentAttempt(saved.attempt)?.run!=execution)return@withTransaction false
                dao.putDistillation(current.copy(
                    state=when(error) {is QuotaExceeded -> "waiting_quota";is ReauthorizationRequired -> "waiting_auth";else -> "failed"},
                    error=if(error is CancellationException)"Generation was interrupted. Your source and any completed candidate are saved." else safeFailure(error)))
                true
            } }
            if(!owned && !alreadyTerminal)summary.discarded()
            if(owned) {
                diagnostics?.failure("distillation",error,saved.id,correlation=evidence)
                if(error is QuotaExceeded || error is ReauthorizationRequired)diagnostics?.event("distillation.queued",saved.id,
                    reason=if(error is QuotaExceeded)"waiting_quota" else "waiting_auth",correlation=evidence)
            }
            if(error is CancellationException)throw error
        } finally {
            // A late result must never masquerade as a published result or a generator abstention.
            // Diagnostics are best effort and cannot replace the application's return/exception.
            runCatching {
                if(!terminalRecorded)summary.discarded()
                if(summary.finish())diagnostics?.attemptSummary(summary,evidence)
            }
        }
    }
}
