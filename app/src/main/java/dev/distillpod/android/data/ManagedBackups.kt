package dev.distillpod.android.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.*
import org.json.JSONObject

/** A user-authorized document is remembered across launches. Drive uploads remain owned by its provider. */
class ManagedBackups(
    private val context: Context,
    private val library: LibraryBackup,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val review: (BackupPreview) -> Unit,
) {
    companion object { private val operations = Mutex() }
    data class Choice(val id: String, val at: Long, val version: String, val shows: Int, val episodes: Int, val moments: Int, val local: Boolean = false)
    data class Location(val uri: Uri, val name: String, val initialized: Boolean) {
        fun json() = JSONObject().put("uri", uri.toString()).put("name", name).put("initialized", initialized).toString()
        companion object {
            fun parse(raw: String) = runCatching { JSONObject(raw).let {
                Location(Uri.parse(it.getString("uri")).also { uri -> require(uri.scheme == "content") }, it.getString("name").take(200), it.getBoolean("initialized"))
            } }.getOrNull()
        }
    }
    data class State(val message: String = "", val working: Boolean = false, val error: Boolean = false,
        val location: Location? = null, val history: List<Choice> = emptyList(), val chooseBackup: Boolean = false, val awaitingDestination: Boolean = false)
    val state = MutableStateFlow(State())
    val destinations = Channel<String>(Channel.BUFFERED)
    private val resolver get() = context.contentResolver
    private val recoveryDirectory = File(context.filesDir, "backup-recovery")
    private fun recovery(location: Location): File {
        val file = File(recoveryDirectory, "${dev.distillpod.core.episodeId("backup", location.uri.toString())}.zip")
        // AtomicFile on older Android versions leaves a .bak after an interrupted replacement.
        if (File(file.path + ".bak").isFile) AtomicFile(file).openRead().close()
        return file
    }
    private suspend fun location() = Location.parse(settings.value("managedBackup").first())
    private fun choice(record: BackupArchive.Record, local: Boolean = false) = Choice(record.id, record.at, record.version, record.shows, record.episodes, record.moments, local)
    private fun status(message: String, working: Boolean = true) { state.value = state.value.copy(message = message, working = working, error = false, chooseBackup = false) }

    private fun operation(message: String, block: suspend (File) -> Unit) {
        if (state.value.working) return
        status(message)
        scope.launch {
            val folder = File(context.cacheDir, "managed-backup-${UUID.randomUUID()}")
            try {
                withContext(Dispatchers.IO) {
                    folder.mkdirs(); recoveryDirectory.mkdirs()
                    require(folder.isDirectory && recoveryDirectory.isDirectory) { "Phone storage is unavailable. Free some space and try again." }
                    operations.withLock { block(folder) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                state.value = state.value.copy(working = false, error = true, chooseBackup = false, message = when (error) {
                    is SecurityException -> "Access to the backup file was lost. Connect the existing file again."
                    is IllegalArgumentException -> error.message?.take(200) ?: "This backup file is not supported."
                    is java.util.zip.ZipException, is org.json.JSONException -> "This is not a valid backup history. Use Import older backup for a standalone JSON file."
                    else -> "The backup file is unavailable. Check Drive’s account and connection, or reconnect the file. Any verified recovery copy is kept on this phone."
                })
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { folder.deleteRecursively() }
                if (state.value.working) state.value = state.value.copy(working = false, error = true, message = "Backup interrupted. Retry when ready; any verified recovery copy has been kept.")
            }
        }
    }

    fun create() {
        if (state.value.working || state.value.awaitingDestination) return
        state.value = state.value.copy(message = "Choose Google Drive and a folder once, then save the suggested file name.", awaitingDestination = true)
        destinations.trySend("DistillPod-backups.zip")
    }
    fun created(uri: Uri?) {
        state.value = state.value.copy(awaitingDestination = false)
        if (uri == null) { state.value = state.value.copy(message = "Setup cancelled. Your library and existing backups were kept."); return }
        operation("Connecting your backup file…") { folder ->
            val previous = location()
            val previousHistory = state.value.history
            persist(uri)
            val selected = Location(uri, name(uri) ?: "DistillPod-backups.zip", false)
            settings.text("managedBackup", selected.json())
            state.value = state.value.copy(location = selected, history = emptyList())
            try { writeBackup(selected, folder, fresh = true) }
            catch (error: Exception) {
                if (previous != null) withContext(NonCancellable) {
                    settings.text("managedBackup", previous.json())
                    state.value = state.value.copy(location = previous, history = previousHistory)
                    if (previous.uri != uri) release(uri)
                }
                throw error
            }
            if (previous != null && previous.uri != uri) release(previous.uri)
        }
    }
    fun connect(uri: Uri?) {
        if (uri == null) return
        operation("Reading backup history…") { folder ->
            val previous = location()
            val file = download(uri, File(folder, "connected.zip"))
            val snapshots = BackupArchive.read(file, File(folder, "snapshots"))
            persist(uri) // Do not replace an existing connection unless this file is valid and access can be retained.
            val selected = Location(uri, name(uri) ?: "DistillPod backup file", true)
            atomicCopy(recovery(selected), file)
            settings.text("managedBackup", selected.json())
            if (previous != null && previous.uri != uri) release(previous.uri)
            state.value = State("Backup file connected. Future saves and restores use this file.", location = selected, history = snapshots.map { choice(it.record) })
        }
    }
    fun backup() = operation("Preparing your library backup…") { folder ->
        val selected = location() ?: throw IllegalArgumentException("Create or connect a backup file first.")
        state.value = state.value.copy(location = selected)
        writeBackup(selected, folder)
    }
    fun refresh(choose: Boolean = false) = operation("Loading saved backups…") { folder ->
        val selected = location()
        if (selected == null) { state.value = State(); return@operation }
        state.value = state.value.copy(location = selected)
        try {
            val file = download(selected.uri, File(folder, "history.zip"))
            val snapshots = BackupArchive.read(file, File(folder, "snapshots"))
            atomicCopy(recovery(selected), file)
            state.value = State(location = selected, history = snapshots.map { choice(it.record) }, chooseBackup = choose)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val cached = recovery(selected)
            if (!cached.isFile) throw error
            val snapshots = BackupArchive.read(cached, File(folder, "cached"))
            state.value = State("Drive is unavailable. Showing the last verified copy kept on this phone.", error = true, location = selected,
                history = snapshots.map { choice(it.record, true) }, chooseBackup = choose)
        }
    }
    fun dismissChoices() { if (!state.value.working) state.value = state.value.copy(chooseBackup = false) }
    fun preview(id: String, local: Boolean) = operation("Checking the selected backup…") { folder ->
        val selected = location() ?: throw IllegalArgumentException("Connect a backup file first.")
        val file = if (local) recovery(selected) else download(selected.uri, File(folder, "selected.zip"))
        val snapshots = BackupArchive.read(file, File(folder, "snapshots"))
        val snapshot = snapshots.firstOrNull { it.record.id == id } ?: throw IllegalArgumentException("That backup changed. Refresh the history and choose again.")
        val preview = snapshot.file.inputStream().use { library.preview(it) }
        state.value = state.value.copy(message = "", working = false, error = false, chooseBackup = false)
        review(preview)
    }
    fun disconnect() = operation("Disconnecting the backup file…") { _ ->
        // Disconnecting changes only this installation's preference; it never deletes cloud files or recovery data.
        val previous = location()
        settings.text("managedBackup", "")
        previous?.let { release(it.uri) }
        state.value = State("Backup file disconnected. Its saved backups were kept.")
    }

    private suspend fun writeBackup(selected: Location, folder: File, fresh: Boolean = false) {
        var original: File? = null
        var recovered = false
        val remote = if (fresh) null else download(selected.uri, File(folder, "previous.zip"))
        if (remote != null && (remote.length() > 0 || selected.initialized)) {
            try { BackupArchive.read(remote, File(folder, "validated-previous")); original = remote }
            catch (error: Exception) {
                if (error is CancellationException || error is BackupArchive.Unsupported) throw error
                if (selected.initialized) {
                    val cached = recovery(selected)
                    require(cached.isFile) { "The backup file is damaged and no recovery copy is available. Create another backup file; this file will be kept." }
                    BackupArchive.read(cached, File(folder, "validated-recovery"))
                    original = cached; recovered = true
                }
            }
        }
        val safeOriginal = original
        val previous = safeOriginal?.let { BackupArchive.read(it, File(folder, "previous")) }.orEmpty()
        val bytes = library.export(PortablePreferences.from(settings.flow.first()), context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty())
        val preview = library.preview(bytes.inputStream())
        val snapshot = File(folder, "current.json").apply { writeBytes(bytes) }
        val archive = File(folder, "next.zip")
        val retained = BackupArchive.write(archive, listOf(BackupArchive.snapshot(snapshot, preview)) + previous)
        if (safeOriginal != null) atomicCopy(recovery(selected), safeOriginal)
        status("Saving to your connected backup file…")
        if (remote != null) require(remoteHash(selected.uri) == BackupArchive.hash(remote)) { "The backup file changed elsewhere. Refresh the history and try again." }
        try {
            upload(selected.uri, archive)
            status("Checking the saved backup…")
            require(remoteHash(selected.uri) == BackupArchive.hash(archive)) { "The saved backup is incomplete. Your recovery copy is kept on this phone." }
        } catch (error: Exception) {
            // Never replace an independently updated, valid archive with a stale recovery copy.
            if (safeOriginal != null) withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    val current = download(selected.uri, File(folder, "failed-write.zip"))
                    val isValid = try { BackupArchive.read(current, File(folder, "failed-snapshots")); true }
                    catch (_: BackupArchive.Unsupported) { true }
                    catch (_: Exception) { false }
                    if (!isValid) { upload(selected.uri, safeOriginal); check(remoteHash(selected.uri) == BackupArchive.hash(safeOriginal)) }
                }
            }
            throw error
        }
        // A metadata failure must not delete or roll back a verified cloud backup.
        val warnings = mutableListOf<String>()
        try { atomicCopy(recovery(selected), archive) } catch (_: IOException) { warnings += "The recovery copy could not be updated on this phone." }
        val connected = selected.copy(initialized = true)
        try { settings.text("managedBackup", connected.json()) } catch (error: Exception) { if (error is CancellationException) throw error; warnings += "Connection details could not be saved. Reconnect this file after restarting." }
        state.value = State((if (recovered) "Backup repaired and saved. " else "Backup saved and checked. ") +
            "Drive manages cloud upload and sync." + warnings.joinToString(" ", prefix = if (warnings.isEmpty()) "" else " "),
            error = warnings.isNotEmpty(), location = connected, history = retained.map { choice(it.record) })
    }
    private fun release(uri: Uri) { runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
    private fun persist(uri: Uri) {
        require(uri.scheme == "content") { "Choose a backup through Android’s file picker." }
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    private fun name(uri: Uri) = runCatching { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), android.os.Bundle.EMPTY, null)?.use {
        if (it.moveToFirst()) it.getString(0)?.filterNot(Char::isISOControl)?.take(200) else null
    } }.getOrNull()
    private fun download(uri: Uri, file: File): File {
        resolver.openInputStream(uri)?.use { input -> file.outputStream().use { BackupArchive.copy(input, it, BackupArchive.MAX_FILE) } } ?: throw IOException("No backup input stream")
        return file
    }
    private fun upload(uri: Uri, file: File) {
        resolver.openOutputStream(uri, "wt")?.use { output -> file.inputStream().use { BackupArchive.copy(it, output, BackupArchive.MAX_FILE) } } ?: throw IOException("No backup output stream")
    }
    private fun remoteHash(uri: Uri) = resolver.openInputStream(uri)?.use(BackupArchive::hash) ?: throw IOException("No verification stream")
    private fun atomicCopy(destination: File, source: File) {
        if (destination.canonicalFile == source.canonicalFile) return
        val atomic = AtomicFile(destination)
        val output = atomic.startWrite()
        try { source.inputStream().use { BackupArchive.copy(it, output, BackupArchive.MAX_FILE) }; atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
}
