package dev.distillpod.android.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A portable, bounded history. Snapshot JSON retains LibraryBackup's schema and integrity checks. */
internal object BackupArchive {
    class Unsupported(message: String) : IllegalArgumentException(message)
    const val MAX_FILE = 64L * 1024 * 1024
    const val MAX_SNAPSHOT = 32L * 1024 * 1024
    const val MAX_EXPANDED = 128L * 1024 * 1024
    const val KEEP = 10
    data class Record(val id: String, val at: Long, val version: String, val shows: Int, val episodes: Int, val moments: Int,
        val bytes: Long, val sha256: String) {
        val entry get() = "snapshot-$id.json"
        fun json() = JSONObject().put("id", id).put("at", at).put("version", version).put("shows", shows)
            .put("episodes", episodes).put("moments", moments).put("bytes", bytes).put("sha256", sha256)
    }
    data class Snapshot(val record: Record, val file: File)

    fun snapshot(file: File, preview: BackupPreview): Snapshot = Snapshot(Record(UUID.randomUUID().toString(), preview.createdAt,
        preview.appVersion, preview.counts["podcasts"] ?: 0, preview.episodes, preview.moments, file.length(), hash(file)), file)

    fun read(file: File, folder: File): List<Snapshot> {
        if (file.length() > MAX_FILE) throw Unsupported("This history is too large for this version. Choose another backup file or update DistillPod.")
        require(file.length() > 0) { "Backup history is empty" }
        folder.mkdirs()
        return ZipFile(file).use { zip ->
            if (zip.size() > KEEP + 1) throw Unsupported("This history has more snapshots than this version supports. Choose another file or update DistillPod.")
            val entries = zip.entries().asSequence().toList()
            require(entries.size in 2..KEEP + 1 && entries.map { it.name }.distinct().size == entries.size) { "Invalid backup history entries" }
            val manifest = zip.getEntry("manifest.json") ?: throw Unsupported("This file is not a DistillPod backup history. Choose another file.")
            val root = JSONObject(zip.getInputStream(manifest).use { input ->
                val bytes = java.io.ByteArrayOutputStream(); copy(input, bytes, 64 * 1024); bytes.toString("UTF-8")
            })
            if (root.optString("format") != "distillpod-backup-history" || root.optInt("version") != 1) throw Unsupported("This backup history format is not supported. Choose another file or update DistillPod.")
            val list = root.getJSONArray("snapshots")
            require(list.length() in 1..KEEP && list.length() + 1 == entries.size) { "Invalid backup history size" }
            var expanded = 0L
            val snapshots = (0 until list.length()).map { index ->
                val row = list.getJSONObject(index)
                val record = Record(row.getString("id"), row.getLong("at"), row.getString("version"), row.getInt("shows"),
                    row.getInt("episodes"), row.getInt("moments"), row.getLong("bytes"), row.getString("sha256"))
                require(record.id.matches(Regex("[a-f0-9-]{36}")) && record.at >= 0 && record.version.length <= 100 &&
                    record.version.none(Char::isISOControl) && listOf(record.shows, record.episodes, record.moments).all { it in 0..100000 } &&
                    record.bytes in 1..MAX_SNAPSHOT && record.sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid backup history record" }
                expanded += record.bytes
                require(expanded <= MAX_EXPANDED) { "Expanded backup history is too large" }
                val entry = zip.getEntry(record.entry) ?: error("A backup snapshot is missing")
                require(!entry.isDirectory && entry.size == record.bytes) { "Invalid backup snapshot size" }
                val extracted = File.createTempFile("snapshot-", ".json", folder)
                zip.getInputStream(entry).use { input -> extracted.outputStream().use { copy(input, it, record.bytes) } }
                require(extracted.length() == record.bytes && hash(extracted) == record.sha256) { "A backup snapshot integrity check failed" }
                Snapshot(record, extracted)
            }
            require(snapshots.map { it.record.id }.distinct().size == snapshots.size &&
                entries.map { it.name }.toSet() == snapshots.map { it.record.entry }.toSet() + "manifest.json") { "Unexpected backup history entries" }
            snapshots.sortedByDescending { it.record.at }
        }
    }

    fun write(file: File, snapshots: List<Snapshot>): List<Snapshot> {
        require(snapshots.isNotEmpty())
        var total = 0L
        val retained = snapshots.take(KEEP).takeWhile { total += it.record.bytes; total <= MAX_EXPANDED }
        require(retained.isNotEmpty()) { "Backup exceeds the history size limit" }
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(JSONObject().put("format", "distillpod-backup-history").put("version", 1)
                .put("snapshots", JSONArray(retained.map { it.record.json() })).toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            retained.forEach { snapshot ->
                require(snapshot.file.length() == snapshot.record.bytes && hash(snapshot.file) == snapshot.record.sha256) { "Backup snapshot changed while preparing history" }
                zip.putNextEntry(ZipEntry(snapshot.record.entry)); snapshot.file.inputStream().use { copy(it, zip, MAX_SNAPSHOT) }; zip.closeEntry()
            }
        }
        require(file.length() <= MAX_FILE) { "Backup history exceeds 64 MB" }
        return retained
    }

    fun copy(input: InputStream, output: OutputStream, limit: Long): Long {
        var count = 0L; val buffer = ByteArray(65536)
        while (true) {
            val n = input.read(buffer); if (n < 0) break
            count += n; require(count <= limit) { "Backup file exceeds its size limit" }; output.write(buffer, 0, n)
        }
        return count
    }
    fun hash(file: File) = file.inputStream().use(::hash)
    fun hash(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536); var bytes = 0L
        while (true) { val n = input.read(buffer); if (n < 0) break; bytes += n; require(bytes <= MAX_FILE) { "Backup file exceeds 64 MB" }; digest.update(buffer, 0, n) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
