package dev.distillpod.android.data

import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Matching passages of one episode's current transcript: how many, and the earliest one. */
data class TranscriptHit(val episodeId: String, val matches: Int, val startMs: Long, val endMs: Long, val text: String)

/**
 * Finds passages in the transcripts stored on this phone (#179); nothing leaves the device.
 *
 * Only segments of an existing transcript row's current revision are read, so superseded revisions
 * and transcripts that were deleted or suppressed (#60) never match. Every term must occur in the
 * same passage. The segment table is scanned in rowid ranges: a newer query cancels a stale one
 * between ranges, and no schema change or derived index is needed. Ranges are not one snapshot: a
 * transcript rewritten during a scan can be missed or counted from its previous revision until the
 * next search, which reruns when transcripts change.
 */
class TranscriptSearch(private val db: AppDatabase, private val rowsPerRange: Long = 20_000,
    private val beforeRange: () -> Unit = {}) {
    suspend fun find(terms: List<String>): List<TranscriptHit> = withContext(Dispatchers.IO) {
        require(terms.isNotEmpty() && rowsPerRange > 0)
        val patterns = terms.map(::likePatterns)
        val where = patterns.joinToString(" AND ") { variants -> variants.joinToString(" OR ", "(", ")") { "s.text LIKE ? ESCAPE '\\'" } }
        val sql = "SELECT s.episodeId, COUNT(*), MIN(s.startMs), s.endMs, s.text FROM segments s " +
            "CROSS JOIN transcripts t ON t.episodeId=s.episodeId AND t.revision=s.revision " +
            "WHERE s.rowid BETWEEN ? AND ? AND $where GROUP BY s.episodeId"
        val bounds = db.query(SimpleSQLiteQuery("SELECT MIN(rowid), MAX(rowid) FROM segments")).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0)..cursor.getLong(1) else null
        } ?: return@withContext emptyList()
        val hits = LinkedHashMap<String, TranscriptHit>()
        var start = bounds.first
        while (start <= bounds.last) {
            ensureActive()
            beforeRange()
            val end = if (bounds.last - start < rowsPerRange) bounds.last else start + rowsPerRange - 1
            val args: Array<Any> = (listOf<Any>(start, end) + patterns.flatten()).toTypedArray()
            db.query(SimpleSQLiteQuery(sql, args)).use { cursor ->
                while (cursor.moveToNext()) {
                    val hit = TranscriptHit(cursor.getString(0), cursor.getInt(1), cursor.getLong(2), cursor.getLong(3), cursor.getString(4))
                    val old = hits[hit.episodeId]
                    hits[hit.episodeId] = if (old == null) hit
                        else (if (hit.startMs < old.startMs) hit else old).copy(matches = old.matches + hit.matches)
                }
            }
            if (end == bounds.last) break
            start = end + 1
        }
        hits.values.toList()
    }
}

/**
 * LIKE patterns for one term with % and _ matched literally. SQLite folds only ASCII case, so a
 * term with other letters also tries its capitalized and upper-case forms ("água" finds "Água").
 */
internal fun likePatterns(term: String): List<String> {
    val forms = if (term.all { it.code < 128 }) listOf(term)
        else listOf(term.lowercase(), term.lowercase().replaceFirstChar { it.titlecase() }, term.uppercase()).distinct()
    return forms.map { "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" }
}
