package dev.distillpod.android.data

import android.database.sqlite.SQLiteConstraintException

internal enum class TranscriptWriteSite {
    PREPARE_LOOKUP, PREPARE_EXISTING, PREPARE_NEW,
    PUBLISHER_FALLBACK, ONLINE_LOOKUP, AUDIO_FINGERPRINT,
    CHUNK_COMPLETE, CHUNK_PROGRESS, TRANSCRIPT_COMPLETE, PUBLISHER_COMPLETE,
    FEED_ENCLOSURE, DOWNLOAD_FINGERPRINT, FINISHED_CONTENT, ONLINE_ADOPTION,
}

internal suspend fun LibraryDao.putTranscriptAt(value: TranscriptEntity, site: TranscriptWriteSite) {
    try {
        putTranscript(value)
    } catch (failure: SQLiteConstraintException) {
        // Room's suspension boundary can hide the writer. Preserve the failure and
        // add only a fixed site, never row content, IDs or a captured stack.
        failure.addSuppressed(TranscriptWriteFailureSite(site))
        throw failure
    }
}

private class TranscriptWriteFailureSite(site: TranscriptWriteSite) :
    RuntimeException("transcript.write.site:${site.name.lowercase()}", null, false, false)
