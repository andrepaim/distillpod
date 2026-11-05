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

fun jsonObjectAnswer(text: String): JSONObject {
    val stripped = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    return JSONObject(stripped)
}
class CaptureRepository(private val db: AppDatabase, private val ai: AiGateway, private val connected: () -> Boolean,
    private val prepare: suspend (String) -> Unit, private val enqueue: (String) -> Unit,
    private val onPortableChanged: () -> Unit = {}) {
    private val dao = db.library()
    private val portable = dev.distillpod.android.sync.PortableLibrarySync(db)
    private val busy = setOf("waiting_transcript", "queued", "running", "reviewing")

    suspend fun capture(id: String, position: Long, verifiedSource: Boolean): String {
        val saved=save(id,position,verifiedSource)
        prepareSaved(saved)
        return saved
    }
    /** Durable acceptance is independent from preparation, authorization and inference. */
    suspend fun save(id: String, position: Long, verifiedSource: Boolean): String {
        require(position>=0) { "Playback position is invalid" }
        val ep = dao.episode(id) ?: error("Episode is unavailable")
        val saved = db.withTransaction {
            dao.recentCapture(id, position, System.currentTimeMillis() - 2000)
                ?: DistillationEntity(UUID.randomUUID().toString(), id, (position - 60000).coerceAtLeast(0), position,
                    fingerprint = if (verifiedSource) ep.mediaFingerprint else "unverified:${ep.id}", attempt = UUID.randomUUID().toString()).also {
                    dao.putDistillation(it); dao.putMomentAttempt(MomentAttemptEntity(it.attempt,it.id,"{\"expanded\":false}")); portable.recordMoment(it)
                }
        }
        onPortableChanged()
        return saved.id
    }
    suspend fun prepareSaved(id:String) {
        dao.distillation(id)?.takeIf {it.state=="waiting_transcript"}?.let {hydrate(it)}
    }
    suspend fun resumeSaved() {
        dao.distillations().filter {it.state=="waiting_transcript"}.forEach {
            try {prepareSaved(it.id)} catch(cancelled:CancellationException) {throw cancelled} catch(_:Exception) { /* Retain for the next recovery. */ }
        }
    }
    private suspend fun hydrate(saved: DistillationEntity) {
        val ep = dao.episode(saved.episodeId) ?: return
        val attempt = dao.momentAttempt(saved.attempt) ?: MomentAttemptEntity(UUID.randomUUID().toString(),saved.id,"{\"expanded\":false}")
        val expanded = JSONObject(attempt.contextJson).optBoolean("expanded")
        val transcript = dao.transcript(ep.id)?.takeUnless { it.source == "online" }
        // Publisher transcripts can be complete even when the feed has no episode duration.
        // Only a complete, matching transcript can supply the missing end boundary.
        val duration = ep.durationMs.takeIf { it > 0 }
            ?: transcript?.takeIf { it.state == "ready" && it.fingerprint == ep.mediaFingerprint && it.durationMs > 0 }?.durationMs
            ?: 0L
        val range = MomentContexts.range(saved.endMs, duration, expanded)
        val coverage = transcript?.let { dao.coverage(ep.id,it.revision).map { c -> TimeRange(c.startMs,c.endMs) } }.orEmpty()
        val covered = transcript != null && transcript.fingerprint == ep.mediaFingerprint && normalizeRanges(coverage,Long.MAX_VALUE)
            .any { it.startMs <= range.startMs && it.endMs >= range.endMs }
        if (!covered) { prepare(ep.id); return }
        val segments = dao.window(ep.id,transcript!!.revision,range.startMs,range.endMs)
        val context = try { MomentContexts.build(TimeRange(saved.startMs,saved.endMs),range,
            segments.map { SubtitleSegment(TimeRange(it.startMs,it.endMs),it.text) }) }
        catch (_: IllegalArgumentException) {
            db.withTransaction { dao.distillation(saved.id)?.takeIf { it.attempt==saved.attempt && it.state=="waiting_transcript" }?.let {
                dao.putDistillation(it.copy(state="needs_context",error="This source needs a shorter, clearer passage. Your moment is saved."))
            } };return
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
        val changed = db.withTransaction {
            val current=dao.distillation(saved.id)
            if(current?.state!="waiting_transcript" || current.attempt!=saved.attempt) false else {
                dao.putMomentAttempt(attempt.copy(contextJson=MomentGeneration.contextJson(context,transcript.revision,ep.mediaFingerprint,original,expanded)))
                val updated=current.copy(excerpt=current.excerpt.ifBlank { raw },attempt=attempt.id,
                    revision=if(current.insight==null)transcript.revision else current.revision,state=state,
                    error=when(state) { "no_speech" -> "No transcribed speech was found in this window."; "needs_context" -> "No complete source segments fit this window. Expand the context."; else -> null })
                dao.putDistillation(updated);if(updated.insight==null)portable.recordMoment(updated);true
            }
        }
        if(changed)onPortableChanged()
        if(changed && state=="queued")enqueue(saved.id)
    }

    suspend fun transcriptUpdated(id: String) {
        dao.distillations().filter { it.episodeId==id && it.state=="waiting_transcript" }.forEach { hydrate(it) }
    }

    /** Explicit regeneration keeps the published result until a replacement has passed review. */
    suspend fun improve(id: String, expanded: Boolean = false) {
        val pending=db.withTransaction {
            val current=dao.distillation(id) ?: return@withTransaction null
            if(current.state in busy)return@withTransaction null
            val attempt=MomentAttemptEntity(UUID.randomUUID().toString(),id,JSONObject().put("expanded",expanded).toString())
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
            improve(id,ctx.optBoolean("expanded"));return
        }
        if(saved.fingerprint!=ep.mediaFingerprint && !confirmSource || saved.state=="review_source" && !confirmSource) {
            dao.putDistillation(saved.copy(state="review_source",error="Review this source before generating."));return
        }
        // Content rejections are new attempts. Failed review requests resume the saved candidate.
        if(attempt.state in setOf("needs_context","unclear_source","rejected","succeeded")) { improve(id,ctx.optBoolean("expanded"));return }
        val queued=db.withTransaction {
            val current=dao.distillation(id) ?: return@withTransaction false
            if(current.attempt!=saved.attempt || current.state in busy)return@withTransaction false
            dao.putMomentAttempt(attempt.copy(run=UUID.randomUUID().toString()))
            dao.putDistillation(current.copy(state=if(connected())"queued" else "waiting_auth",fingerprint=if(current.insight==null)ep.mediaFingerprint else current.fingerprint,error=null));true
        }
        if(queued && connected())enqueue(id)
    }

    suspend fun cancel(id: String) = db.withTransaction {
        dao.distillation(id)?.takeIf { it.state in busy }?.let {
            dao.momentAttempt(it.attempt)?.let { a -> dao.putMomentAttempt(a.copy(run=UUID.randomUUID().toString())) }
            dao.putDistillation(it.copy(state="failed",error="Generation paused. Your saved result and source are kept."))
        }
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

    private suspend fun valid(id: String, attempt: MomentAttemptEntity): DistillationEntity? {
        val current=dao.distillation(id) ?: return null
        if(current.attempt!=attempt.id || current.state !in setOf("running","reviewing") || dao.momentAttempt(attempt.id)?.run!=attempt.run)return null
        val ctx=JSONObject(attempt.contextJson)
        check(dao.episode(current.episodeId)?.mediaFingerprint==ctx.getString("fingerprint") &&
            dao.transcript(current.episodeId)?.revision==ctx.getString("revision")) { "The transcript or audio changed. Prepare the source again." }
        return current
    }

    suspend fun run(id: String) {
        val saved=db.withTransaction {
            dao.distillation(id)?.takeIf { it.state=="queued" }?.also { dao.putDistillation(it.copy(state="running",error=null)) }
        } ?: return
        var execution: String? = null
        try {
            var attempt=dao.momentAttempt(saved.attempt) ?: error("Prepare the source again before generating")
            execution=attempt.run
            val context=MomentGeneration.context(attempt.contextJson)
            check(context.spans.isNotEmpty()) { "No usable source context" }
            if(valid(id,attempt)==null)return
            if(attempt.candidateJson.isBlank()) {
                val counted=attempt.copy(generationCalls=attempt.generationCalls+1,state="generating")
                if(!db.withTransaction {if(valid(id,attempt)==null)false else {dao.putMomentAttempt(counted);true}})return
                attempt=counted
                val started=System.nanoTime()
                val answer=ai.complete(MomentGeneration.generatePrompt,MomentGeneration.input(attempt.contextJson))
                val candidate=MomentGeneration.candidate(answer.text,context)
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
                if(!accepted || candidate.outcome!="ready")return
                attempt=next
            }
            val candidate=MomentGeneration.candidate(attempt.candidateJson,context)
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
                val answer=ai.complete(MomentGeneration.reviewPrompt,input.toString())
                MomentGeneration.review(answer.text,context)
                val next=attempt.copy(reviewJson=answer.text,reviewModel=answer.model,reviewMs=(System.nanoTime()-started)/1_000_000)
                if(!db.withTransaction { if(valid(id,attempt)==null)false else {dao.putMomentAttempt(next);true} })return
                attempt=next
            }
            val review=MomentGeneration.review(attempt.reviewJson,context)
            db.withTransaction {
                val current=valid(id,attempt) ?: return@withTransaction
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
            };onPortableChanged()
        } catch(error: Exception) {
            withContext(NonCancellable) { db.withTransaction {
                val current=dao.distillation(id)
                if(current!=null && current.attempt==saved.attempt && current.state in setOf("running","reviewing") && dao.momentAttempt(saved.attempt)?.run==execution)dao.putDistillation(current.copy(
                    state=when(error) {is QuotaExceeded -> "waiting_quota";is ReauthorizationRequired -> "waiting_auth";else -> "failed"},
                    error=if(error is CancellationException)"Generation was interrupted. Your source and any completed candidate are saved." else safeFailure(error)))
            } }
            if(error is CancellationException)throw error
        }
    }
}
