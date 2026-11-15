package dev.distillpod.android.data

import dev.distillpod.android.sync.PortableLibrarySync
import dev.distillpod.core.episodeId
import dev.distillpod.core.sync.MediaLocator
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONObject

/** The identity a feed gives an episode before any download, derived from its enclosure URL (#245). */
internal fun provisionalFingerprint(ep: EpisodeEntity) = episodeId("enclosure", ep.audioUrl)

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
    if (previous == provisionalFingerprint(latest) && followIdentity(db, latest.id, previous, sha256, TranscriptWriteSite.DOWNLOAD_FINGERPRINT)) return
    val dao = db.library()
    dao.transcript(latest.id)?.let { dao.putTranscriptAt(it.copy(state = "stale", error = "Audio changed. Prepare the transcript again."), TranscriptWriteSite.DOWNLOAD_FINGERPRINT) }
    dao.analysis(latest.id)?.let { dao.putAnalysis(it.copy(state = "stale", error = "Audio changed. Analyze the new transcript.")) }
}

/**
 * The identity synced work tied to [incoming] takes on this phone, or null when it describes other
 * audio (#250). One installation may only have streamed an episode while another downloaded it, so the
 * same audio arrives under the provisional identity or under a file hash:
 * - work made from this phone's provisional identity takes this phone's file hash;
 * - while this phone only has the provisional identity, a hash for the same portable enclosure
 *   ([audioUrl] as the record names it) is adopted, as a first download here would adopt its own.
 * Different file hashes, other enclosures and unverified or blank identities never match.
 */
internal fun syncedSourceFingerprint(ep: EpisodeEntity, incoming: String, audioUrl: String?): String? {
    val provisional = provisionalFingerprint(ep)
    return when {
        incoming == ep.mediaFingerprint -> incoming
        incoming == provisional -> ep.mediaFingerprint
        ep.mediaFingerprint == provisional && incoming.isNotBlank() && !incoming.startsWith("unverified:") &&
            audioUrl != null && audioUrl == MediaLocator.portable(ep.audioUrl, ep.guid) -> incoming
        else -> null
    }
}

/**
 * Gives a streaming-only episode the file hash [fingerprint] accepted from another installation, moving
 * local work tied to its provisional identity with it. Both installations then describe the episode and
 * its finished content identically, so neither sends the content back as a change. Runs inside the
 * transaction that stores the synced work; returns the episode as stored.
 */
internal suspend fun adoptSyncedFingerprint(db: AppDatabase, ep: EpisodeEntity, fingerprint: String): EpisodeEntity {
    val dao = db.library()
    val latest = dao.episode(ep.id) ?: return ep
    val provisional = provisionalFingerprint(latest)
    if (latest.mediaFingerprint == fingerprint || latest.mediaFingerprint != provisional) return latest
    // Moments are not queued again: a record queued during a pull would conflict with the one being
    // projected, and installations with this rule read the provisional identity as the hash anyway.
    followIdentity(db, latest.id, provisional, fingerprint, TranscriptWriteSite.SYNC_FINGERPRINT, recordMoments = false)
    val adopted = latest.copy(mediaFingerprint = fingerprint)
    dao.putEpisode(adopted)
    return adopted
}

/** Moves work tied to [from] to [to]. Returns whether the episode's transcript was among it. */
private suspend fun followIdentity(db: AppDatabase, episode: String, from: String, to: String, site: TranscriptWriteSite,
    recordMoments: Boolean = true): Boolean {
    val dao = db.library()
    dao.retagMessages(episode, from, to)
    retagMoments(db, episode, from, to, recordMoments)
    // The transcript an explicit local replacement keeps readable (#229) follows the episode too.
    dao.previousTranscript(episode)?.takeIf { it.fingerprint == from }
        ?.let { dao.putPreviousTranscript(it.copy(fingerprint = to)) }
    val transcript = dao.transcript(episode)?.takeIf { it.fingerprint == from } ?: return false
    dao.putTranscriptAt(transcript.copy(fingerprint = to), site)
    return true
}

private suspend fun retagMoments(db: AppDatabase, episode: String, from: String, to: String, record: Boolean) {
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
        if (!record) continue
        // Sync bookkeeping is best effort here: it must never fail the completed download or sync.
        try { PortableLibrarySync(db).recordMoment(updated) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* The local moment is retagged; a later moment write records it again. */ }
    }
}
