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
private val backupTables = listOf("podcasts", "episodes", "transcripts", "online_transcripts", "segments", "transcript_coverage", "distillations",
    "analyses", "analysis_parts", "messages", "research", "suggestions", "queue", "player_session", "post_drafts", "moment_attempts", "moment_versions")
data class BackupPreview internal constructor(internal val payload: String, val counts: Map<String, Int>, val conflicts: Int,
    val createdAt: Long = 0, val appVersion: String = "Unknown (older backup)", val preferences: PortablePreferences? = null, internal val assets: Map<String, ByteArray> = emptyMap()) {
    val episodes get() = counts["episodes"] ?: 0
    val moments get() = counts["distillations"] ?: 0
}

/** A bounded, versioned data transfer. Only allowlisted library tables enter this format. */
class LibraryBackup(private val db: AppDatabase, private val assets: dev.distillpod.android.sharing.PostAssets? = null) {
    private data class Column(val name: String, val type: String, val required: Boolean, val primary: Boolean)
    private fun columns(table: String): List<Column> = db.openHelper.readableDatabase.query("PRAGMA table_info(`$table`)").use { cursor ->
        buildList { while (cursor.moveToNext()) add(Column(cursor.getString(1), cursor.getString(2), cursor.getInt(3) != 0, cursor.getInt(5) != 0)) }
    }
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    suspend fun export(preferences: PortablePreferences? = null, appVersion: String = ""): ByteArray = db.withTransaction {
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
        val createdAt = System.currentTimeMillis()
        val payload = if (preferences == null) tables.toString() else JSONObject().put("library", tables)
            .put("preferences", PortablePreferences.parse(preferences.json()).json()).put("createdAt", createdAt).put("appVersion", appVersion.take(100)).toString()
        val bytes = JSONObject().put("format", "distillpod-library").put("version", if (preferences == null) 1 else 2).put("databaseSchema", 7)
            .put("createdAt", createdAt).put("payload", payload).put("sha256", sha(payload)).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= BACKUP_LIMIT) { "This library exceeds the 32 MB backup limit" }
        PostBackupBundle.pack(bytes, PostBackupBundle.references(tables), assets)
    }

    suspend fun preview(input: InputStream): BackupPreview = withContext(Dispatchers.IO) {
        val (bytes, images) = PostBackupBundle.unpack(input.readBytesBounded())
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.optString("format") == "distillpod-library" && root.optInt("version") in 1..2 && root.optInt("databaseSchema") in 1..7) { "This backup format is not supported" }
        val original = root.getString("payload")
        require(sha(original) == root.getString("sha256")) { "The backup integrity check failed" }
        val content = if (root.getInt("version") == 2) JSONObject(original) else null
        val preferences = content?.let { PortablePreferences.parse(it.getJSONObject("preferences")) }
        val createdAt = content?.getLong("createdAt") ?: root.optLong("createdAt")
        require(createdAt >= 0) { "Invalid backup date" }
        val appVersion = content?.getString("appVersion") ?: "Unknown (older backup)"
        require(appVersion.length <= 100 && appVersion.none { it.isISOControl() }) { "Invalid backup app version" }
        val library = content?.getJSONObject("library")?.toString() ?: original
        val payload = if (root.getInt("databaseSchema") == 1) {
            val tables = JSONObject(library)
            val analyses = tables.getJSONArray("analyses")
            repeat(analyses.length()) { analyses.getJSONObject(it).put("model", JSONObject.NULL).put("promptVersion", 1).put("attempt", "") }
            val moments = tables.getJSONArray("distillations")
            repeat(moments.length()) { moments.getJSONObject(it).put("attempt", "") }
            val messages = tables.getJSONArray("messages")
            repeat(messages.length()) { messages.getJSONObject(it).put("sourcesJson", "[]").put("revision", "").put("fingerprint", "").put("model", JSONObject.NULL).put("attempt", "") }
            tables.toString()
        } else library
        val normalized = JSONObject(payload)
        if (root.getInt("databaseSchema") < 3) {
            normalized.put("post_drafts", JSONArray())
            for ((table, field) in listOf("podcasts" to "websiteUrl", "episodes" to "webpageUrl")) {
                val rows = normalized.getJSONArray(table)
                repeat(rows.length()) { rows.getJSONObject(it).put(field, JSONObject.NULL) }
            }
        }
        if (root.getInt("databaseSchema") < 4) {
            normalized.put("online_transcripts", JSONArray())
            val rows = normalized.getJSONArray("transcripts")
            repeat(rows.length()) { rows.getJSONObject(it).put("lookupOnline", 0).put("replaceOnline", 0) }
        }
        if (root.getInt("databaseSchema") < 6) {
            normalized.put("moment_attempts", JSONArray()).put("moment_versions", JSONArray())
            val rows = normalized.getJSONArray("distillations")
            repeat(rows.length()) { rows.getJSONObject(it).put("sourceJson", "") }
        }
        if (root.getInt("databaseSchema") < 7) {
            val rows = normalized.getJSONArray("episodes")
            repeat(rows.length()) { rows.getJSONObject(it).put("sourceOrder", JSONObject.NULL) }
        }
        db.withTransaction {
            val tables = validated(normalized.toString())
            require(PostBackupBundle.references(tables) == images.keys) { "Backup infographic assets do not match the library" }
            mapExistingIdentities(tables)
            val counts = backupTables.associateWith { tables.getJSONArray(it).length() }
            var conflicts = 0
            backupTables.forEach { table ->
                val keys = columns(table).filter { it.primary }
                val rows = tables.getJSONArray(table)
                repeat(rows.length()) { i -> if (exists(table, keys, rows.getJSONObject(i))) conflicts++ }
            }
            BackupPreview(normalized.toString(), counts, conflicts, createdAt, appVersion, preferences, images)
        }
    }

    /** Keep existing rows. A malformed or conflicting relationship rolls the entire import back. */
    suspend fun merge(preview: BackupPreview): Int = db.withTransaction {
        val tables = validated(preview.payload)
        require(PostBackupBundle.references(tables) == preview.assets.keys) { "Backup infographic assets changed" }
        if (preview.assets.isNotEmpty()) {
            require(assets != null) { "Infographic storage is unavailable" }
            preview.assets.forEach { (hash, bytes) -> require(assets.put(bytes) == hash) }
        }
        // Immutable files precede the transaction commit: a failed merge may leave harmless unreferenced
        // blobs, but can never commit a post pointing to an absent image or remove a concurrent image.
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
                    if(table=="moment_versions") {
                        val parent=db.library().distillation(row.getString("momentId")) ?: error("Missing saved moment")
                        restoreMomentSnapshot(parent,row.getString("snapshot"))
                    }
                    if(table=="moment_attempts") {
                        val parent=db.library().distillation(row.getString("momentId")) ?: error("Missing saved moment")
                        val context=JSONObject(row.getString("contextJson"))
                        if(context.has("spans"))require(context.getLong("captureStartMs")==parent.startMs && context.getLong("captureEndMs")==parent.endMs) { "Attempt belongs to a different capture" }
                    }
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
                listOf("feedUrl", "audioUrl", "transcriptUrl", "originalFeedUrl", "imageUrl", "websiteUrl", "webpageUrl").forEach { name ->
                    if (row.has(name) && !row.isNull(name) && row.getString(name).isNotBlank()) publicHttpUrl(row.getString(name))
                }
                listOf("positionMs", "durationMs", "startMs", "endMs", "processedMs").forEach { name ->
                    if (row.has(name)) require(row.getLong(name) >= 0) { "Invalid backup timestamp" }
                }
                if (row.has("startMs") && row.has("endMs")) require(row.getLong("endMs") >= row.getLong("startMs")) { "Invalid backup time range" }
                if (table == "player_session") require(row.getDouble("speed") in 0.5..3.0 && row.getInt("id") == 0) { "Invalid player settings" }
                if (table == "post_drafts") SocialResult.parse(row.getString("content"))
                if (table == "distillations") MomentGeneration.validateSource(row.getString("sourceJson"), row.getLong("startMs"), row.getLong("endMs"))
                if (table == "online_transcripts") {
                    val segments = row.getString("segmentsJson")
                    require(row.getString("contentHash") == dev.distillpod.core.episodeId("online-transcript", segments)) { "Online transcript integrity check failed" }
                    decodeOnlineSegments(row.getString("episodeId"), row.getString("revision"), segments, row.getLong("durationMs"))
                    require(row.getInt("selected") in 0..1)
                }
                portable(table, row)
            }
        }
        val moments=tables.getJSONArray("distillations")
        val byId=(0 until moments.length()).map { moments.getJSONObject(it) }.associateBy { it.getString("id") }
        val versions=tables.getJSONArray("moment_versions")
        repeat(versions.length()) { i ->
            val row=versions.getJSONObject(i)
            val moment=byId[row.getString("momentId")] ?: error("Missing moment for saved version")
            restoreMomentSnapshot(DistillationEntity(moment.getString("id"),moment.getString("episodeId"),moment.getLong("startMs"),moment.getLong("endMs")),row.getString("snapshot"))
        }
        val attempts=tables.getJSONArray("moment_attempts")
        repeat(attempts.length()) { i ->
            val row=attempts.getJSONObject(i)
            require(byId.containsKey(row.getString("momentId")))
            val context=JSONObject(row.getString("contextJson"))
            if(context.has("spans")) {
                val parsed=MomentGeneration.context(context.toString())
                require(parsed.spans.sumOf { it.text.length+1 }<=dev.distillpod.core.MomentContexts.MAX_TEXT)
            }
            require(row.getLong("generationCalls")>=0 && row.getLong("reviewCalls")>=0)
        }
        return tables
    }

    private fun portable(table: String, row: JSONObject) {
        if(table=="moment_attempts") {
            row.put("contextJson",JSONObject(row.getString("contextJson")).apply {remove("_diagnostics")}.toString())
        }
        if (table == "post_drafts") {
            val post = SocialResult.parse(row.getString("content"))
            if (post.stage in listOf("queued", "preparing", "researching", "illustrating")) row.put("content",post.copy(stage="failed", attempt=java.util.UUID.randomUUID().toString(), error="Restored from backup. Retry when ready.").json())
        }
        if (table == "episodes") {
            row.put("localFile", JSONObject.NULL).put("downloadState", "none").put("downloadedBytes", 0)
                .put("etag", JSONObject.NULL).put("lastModified", JSONObject.NULL).put("error", JSONObject.NULL)
        }
        val state = row.optString("state")
        if (table == "transcripts") {
            if (state in listOf("processing", "queued")) row.put("state", "paused")
            row.put("lookupOnline", 0).put("replaceOnline", 0)
        }
        if (table in listOf("distillations", "analyses", "messages", "research") && state in listOf("running", "reviewing", "queued", "processing", "waiting_transcript")) {
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
