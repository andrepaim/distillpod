package dev.distillpod.android.data

import dev.distillpod.android.sync.PortableLibrarySync
import dev.distillpod.core.episodeId
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONObject

/**
 * Feeds give an episode a provisional identity derived from its enclosure URL (#245). The first
 * completed download replaces it with the file's SHA-256 without implying different audio, so work
 * tied to the provisional identity follows the episode to the hash. Every other identity change, for
 * example a re-download whose bytes differ, still marks the transcript and analysis stale.
 *
 * Runs inside the download completion transaction with the episode row read in that transaction.
 */
internal suspend fun adoptDownloadedFingerprint(db: AppDatabase, latest: EpisodeEntity, sha256: String) {
    val previous = latest.mediaFingerprint
    if (previous == sha256) return
    val dao = db.library()
    val transcript = dao.transcript(latest.id)
    if (previous == episodeId("enclosure", latest.audioUrl)) {
        dao.retagMessages(latest.id, previous, sha256)
        retagMoments(db, latest.id, previous, sha256)
        if (transcript?.fingerprint == previous) {
            dao.putTranscriptAt(transcript.copy(fingerprint = sha256), TranscriptWriteSite.DOWNLOAD_FINGERPRINT)
            return
        }
    }
    transcript?.let { dao.putTranscriptAt(it.copy(state = "stale", error = "Audio changed. Prepare the transcript again."), TranscriptWriteSite.DOWNLOAD_FINGERPRINT) }
    dao.analysis(latest.id)?.let { dao.putAnalysis(it.copy(state = "stale", error = "Audio changed. Analyze the new transcript.")) }
}

private suspend fun retagMoments(db: AppDatabase, episode: String, from: String, to: String) {
    val dao = db.library()
    for (moment in dao.distillations().filter { it.episodeId == episode }) {
        // A prepared or running attempt checks its frozen context against the episode identity.
        for (attempt in dao.momentAttempts(moment.id)) {
            val context = runCatching { JSONObject(attempt.contextJson) }.getOrNull() ?: continue
            if (context.optString("fingerprint") == from) dao.putMomentAttempt(attempt.copy(contextJson = context.put("fingerprint", to).toString()))
        }
        // Unverified moments keep their explicit source review.
        if (moment.fingerprint != from) continue
        val updated = moment.copy(fingerprint = to)
        dao.putDistillation(updated)
        // Sync bookkeeping is best effort here: it must never fail the completed download.
        try { PortableLibrarySync(db).recordMoment(updated) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* The local moment is retagged; a later moment write records it again. */ }
    }
}
