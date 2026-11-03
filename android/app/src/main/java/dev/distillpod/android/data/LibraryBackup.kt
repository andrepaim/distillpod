package dev.distillpod.android.data

import android.database.Cursor
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val BACKUP_LIMIT = 32 * 1024 * 1024
private val backupTables = listOf("podcasts", "episodes", "transcripts", "segments", "transcript_coverage", "distillations",
    "analyses", "analysis_parts", "messages", "research", "suggestions", "queue", "player_session")
data class BackupPreview internal constructor(internal val payload: String, val counts: Map<String, Int>, val conflicts: Int) {
    val episodes get() = counts["episodes"] ?: 0
    val moments get() = counts["distillations"] ?: 0
}

/** A bounded, versioned data transfer. Only allowlisted library tables enter this format. */
class LibraryBackup(private val db: AppDatabase) {
    private data class Column(val name: String, val type: String, val required: Boolean, val primary: Boolean)
    private fun columns(table: String): List<Column> = db.openHelper.readableDatabase.query("PRAGMA table_info(`$table`)").use { cursor ->
        buildList { while (cursor.moveToNext()) add(Column(cursor.getString(1), cursor.getString(2), cursor.getInt(3) != 0, cursor.getInt(5) != 0)) }
    }
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    suspend fun export(): ByteArray = db.withTransaction {
        val tables = JSONObject()
        var count = 0
        for (table in backupTables) {
            val rows = JSONArray()
            db.openHelper.readableDatabase.query("SELECT * FROM `$table`").use { cursor ->
                while (cursor.moveToNext()) {
                    require(++count <= 100000) { "This library exceeds the backup record limit" }
                    val row = JSONObject()
                    cursor.columnNames.forEachIndexed { i, name -> row.put(name, when (cursor.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                        Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                        else -> error("Unsupported library value")
                    }) }
                    portable(table, row)
                    rows.put(row)
                }
            }
            tables.put(table, rows)
        }
        val payload = tables.toString()
        val bytes = JSONObject().put("format", "distillpod-library").put("version", 1).put("databaseSchema", 2)
            .put("createdAt", System.currentTimeMillis()).put("payload", payload).put("sha256", sha(payload)).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= BACKUP_LIMIT) { "This library exceeds the 32 MB backup limit" }
        bytes
    }

    suspend fun preview(input: InputStream): BackupPreview = withContext(Dispatchers.IO) {
        val bytes = input.readBytesBounded()
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.optString("format") == "distillpod-library" && root.optInt("version") == 1 && root.optInt("databaseSchema") in 1..2) { "This backup format is not supported" }
        val original = root.getString("payload")
        require(sha(original) == root.getString("sha256")) { "The backup integrity check failed" }
        val payload = if (root.getInt("databaseSchema") == 1) {
            val tables = JSONObject(original)
            val analyses = tables.getJSONArray("analyses")
            repeat(analyses.length()) { analyses.getJSONObject(it).put("model", JSONObject.NULL).put("promptVersion", 1).put("attempt", "") }
            val moments = tables.getJSONArray("distillations")
            repeat(moments.length()) { moments.getJSONObject(it).put("attempt", "") }
            val messages = tables.getJSONArray("messages")
            repeat(messages.length()) { messages.getJSONObject(it).put("sourcesJson", "[]").put("revision", "").put("fingerprint", "").put("model", JSONObject.NULL).put("attempt", "") }
            tables.toString()
        } else original
        db.withTransaction {
            val tables = validated(payload)
            mapExistingIdentities(tables)
            val counts = backupTables.associateWith { tables.getJSONArray(it).length() }
            var conflicts = 0
            backupTables.forEach { table ->
                val keys = columns(table).filter { it.primary }
                val rows = tables.getJSONArray(table)
                repeat(rows.length()) { i -> if (exists(table, keys, rows.getJSONObject(i))) conflicts++ }
            }
            BackupPreview(payload, counts, conflicts)
        }
    }

    /** Keep existing rows. A malformed or conflicting relationship rolls the entire import back. */
    suspend fun merge(preview: BackupPreview): Int = db.withTransaction {
        val tables = validated(preview.payload)
        mapExistingIdentities(tables)
        var inserted = 0
        var queueOrder = db.library().queue().maxOfOrNull { it.sortOrder } ?: -1
        for (table in backupTables) {
            val cols = columns(table)
            val rows = tables.getJSONArray(table)
            repeat(rows.length()) { i ->
                val row = rows.getJSONObject(i)
                if (!exists(table, cols.filter { it.primary }, row)) {
                    portable(table, row)
                    if (table == "queue") row.put("sortOrder", ++queueOrder)
                    val values = cols.map { column -> if (row.isNull(column.name)) null else row.get(column.name) }.toTypedArray()
                    db.openHelper.writableDatabase.execSQL("INSERT INTO `$table` (${cols.joinToString { "`${it.name}`" }}) VALUES (${cols.joinToString { "?" }})", values)
                    inserted++
                }
            }
        }
        inserted
    }

    private suspend fun mapExistingIdentities(tables: JSONObject) {
        val dao = db.library()
        val podcasts = tables.getJSONArray("podcasts")
        val podcastIds = mutableMapOf<String, String>()
        repeat(podcasts.length()) { i ->
            val row = podcasts.getJSONObject(i)
            val oldId = row.getString("id")
            val existing = dao.podcastByUrl(row.getString("feedUrl")) ?: row.optString("originalFeedUrl").takeIf { it.isNotBlank() }?.let { dao.podcastByUrl(it) }
            val mapped = existing?.id ?: oldId
            podcastIds[oldId] = mapped; row.put("id", mapped)
        }
        val existingEpisodes = dao.episodes().associateBy { it.podcastId to it.guid }
        val episodeIds = mutableMapOf<String, String>()
        val episodes = tables.getJSONArray("episodes")
        repeat(episodes.length()) { i ->
            val row = episodes.getJSONObject(i)
            val oldId = row.getString("id")
            val oldPodcast = row.getString("podcastId")
            val podcast = podcastIds[oldPodcast] ?: oldPodcast
            val guid = row.getString("guid")
            val mapped = existingEpisodes[podcast to guid]?.id ?: if (podcast != oldPodcast) dev.distillpod.core.episodeId(podcast, guid) else oldId
            episodeIds[oldId] = mapped
            row.put("id", mapped).put("podcastId", podcast)
        }
        backupTables.filter { it !in listOf("podcasts", "episodes", "suggestions") }.forEach { table ->
            val rows = tables.getJSONArray(table)
            repeat(rows.length()) { i ->
                val row = rows.getJSONObject(i)
                if (row.has("episodeId") && !row.isNull("episodeId")) episodeIds[row.getString("episodeId")]?.let { row.put("episodeId", it) }
                if (table == "transcripts") {
                    val current = dao.episode(row.getString("episodeId"))
                    if (current != null && current.mediaFingerprint != row.getString("fingerprint")) row.put("state", "stale").put("error", "Restored transcript belongs to an older audio source. Prepare the current audio again.")
                }
            }
        }
    }

    private fun exists(table: String, keys: List<Column>, row: JSONObject): Boolean {
        check(keys.isNotEmpty())
        return db.openHelper.readableDatabase.query("SELECT 1 FROM `$table` WHERE ${keys.joinToString(" AND ") { "`${it.name}`=?" }} LIMIT 1",
            keys.map { row.get(it.name).toString() }.toTypedArray()).use { it.moveToFirst() }
    }

    private fun validated(payload: String): JSONObject {
        require(payload.toByteArray(Charsets.UTF_8).size <= BACKUP_LIMIT) { "Backup is too large" }
        val tables = JSONObject(payload)
        require(tables.keys().asSequence().toSet() == backupTables.toSet()) { "Unexpected backup tables" }
        var count = 0
        backupTables.forEach { table ->
            val cols = columns(table)
            val rows = tables.getJSONArray(table)
            val ids = hashSetOf<String>()
            repeat(rows.length()) { index ->
                require(++count <= 100000) { "Backup has too many records" }
                val row = rows.getJSONObject(index)
                require(row.keys().asSequence().toSet() == cols.map { it.name }.toSet()) { "Backup columns do not match this version" }
                cols.forEach { col ->
                    val value = row.get(col.name)
                    if (value == JSONObject.NULL) require(!col.required && !col.primary) { "Required backup value is missing" }
                    else when (col.type) {
                        "TEXT" -> require(value is String && value.length <= 1_000_000 && (!col.primary || value.isNotBlank())) { "Invalid backup text" }
                        "INTEGER" -> require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble()) { "Invalid backup number" }
                        "REAL" -> require(value is Number && value.toDouble().isFinite()) { "Invalid backup number" }
                        else -> error("Unsupported backup column")
                    }
                }
                require(ids.add(JSONArray(cols.filter { it.primary }.map { row.get(it.name) }).toString())) { "Duplicate backup record" }
                listOf("feedUrl", "audioUrl", "transcriptUrl", "originalFeedUrl", "imageUrl").forEach { name ->
                    if (row.has(name) && !row.isNull(name) && row.getString(name).isNotBlank()) publicHttpUrl(row.getString(name))
                }
                listOf("positionMs", "durationMs", "startMs", "endMs", "processedMs").forEach { name ->
                    if (row.has(name)) require(row.getLong(name) >= 0) { "Invalid backup timestamp" }
                }
                if (row.has("startMs") && row.has("endMs")) require(row.getLong("endMs") >= row.getLong("startMs")) { "Invalid backup time range" }
                if (table == "player_session") require(row.getDouble("speed") in 0.5..3.0 && row.getInt("id") == 0) { "Invalid player settings" }
                portable(table, row)
            }
        }
        return tables
    }

    private fun portable(table: String, row: JSONObject) {
        if (table == "episodes") {
            row.put("localFile", JSONObject.NULL).put("downloadState", "none").put("downloadedBytes", 0)
                .put("etag", JSONObject.NULL).put("lastModified", JSONObject.NULL).put("error", JSONObject.NULL)
        }
        val state = row.optString("state")
        if (table == "transcripts" && state in listOf("processing", "queued")) row.put("state", "paused")
        if (table in listOf("distillations", "analyses", "messages", "research") && state in listOf("running", "queued", "processing", "waiting_transcript")) {
            row.put("state", "failed").put("error", "Restored from backup. Review the source and retry when ready.")
        }
    }

    private fun InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val block = ByteArray(65536)
        while (true) { val n = read(block); if (n < 0) break; require(output.size() + n <= BACKUP_LIMIT) { "Backup exceeds 32 MB" }; output.write(block, 0, n) }
        return output.toByteArray()
    }
}
