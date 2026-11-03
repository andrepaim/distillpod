package dev.distillpod.android.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONObject

/** Manual document transfers: the provider owns cloud sync; no Drive API or OAuth is used. */
class BackupDocuments(
    private val context: Context,
    private val library: LibraryBackup,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val synchronizeQueue: suspend () -> Unit,
) {
    data class State(val phase: String = "", val working: Boolean = false, val awaitingDestination: Boolean = false,
        val preview: BackupPreview? = null, val error: Boolean = false)
    data class Saved(val name: String, val at: Long, val bytes: Long, val podcasts: Int, val episodes: Int, val moments: Int) {
        fun json() = JSONObject().put("name", name).put("at", at).put("bytes", bytes).put("podcasts", podcasts).put("episodes", episodes).put("moments", moments).toString()
        companion object {
            fun parse(raw: String): Saved? = runCatching { JSONObject(raw).let {
                Saved(it.getString("name"), it.getLong("at"), it.getLong("bytes"), it.getInt("podcasts"), it.getInt("episodes"), it.getInt("moments"))
            } }.getOrNull()
        }
    }
    private data class Prepared(val file: File, val name: String, val preview: BackupPreview)
    val state = MutableStateFlow(State())
    val destinations = Channel<String>(Channel.BUFFERED)
    val lastSaved = settings.value("lastBackup").map(Saved::parse).stateIn(scope, SharingStarted.Eagerly, null)
    private var prepared: Prepared? = null
    private val directory = File(context.cacheDir, "library-backups")
    // Only this coordinator owns this directory. Stale snapshots after process death contain personal text.
    private val cleanup = scope.async(Dispatchers.IO) { directory.mkdirs(); directory.listFiles()?.forEach { it.delete() } }

    private fun operation(phase: String, after: (State) -> Unit = {}, block: suspend () -> State) {
        if (state.value.working) return
        state.value = State(phase, working = true)
        scope.launch {
            var outcome = State("Transfer interrupted. Please try again.", error = true)
            try { cleanup.await(); outcome = block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                outcome = State(when (error) {
                    is SecurityException -> "File access was lost. Choose the file again and retry."
                    is org.json.JSONException -> "This file is not a valid DistillPod backup. Choose another file."
                    is android.database.sqlite.SQLiteException -> "The library could not be merged or read. No partial library import was kept. Check the backup and try again."
                    is IllegalArgumentException -> error.message?.take(180) ?: "This backup could not be checked."
                    else -> "The file provider could not complete this transfer. Check its connection and available storage, then retry."
                }, error = true)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (!outcome.awaitingDestination) { prepared?.file?.delete(); prepared = null }
                }
                state.value = outcome
                after(outcome)
            }
        }
    }

    fun prepare() {
        if (state.value.working || state.value.awaitingDestination) return
        operation("Preparing a consistent library snapshot…", after = { result ->
            if (result.awaitingDestination) prepared?.let { destinations.trySend(it.name) }
        }) {
            val prefs = PortablePreferences.from(settings.flow.first())
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            val bytes = library.export(prefs, version)
            val preview = library.preview(bytes.inputStream())
            val name = "distillpod-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"))}-${UUID.randomUUID().toString().take(8)}.json"
            withContext(Dispatchers.IO) {
                val file = File.createTempFile("prepared-", ".json", directory)
                prepared = Prepared(file, name, preview)
                file.outputStream().use { it.write(bytes) }
            }
            State("Choose Google Drive or another destination in the file picker.", awaitingDestination = true)
        }
    }

    fun save(uri: Uri?) {
        if (state.value.working) return
        if (uri == null) {
            operation("Cancelling…") { State("Save cancelled. Your library is unchanged.") }
            return
        }
        operation("Saving to the selected file provider…") {
            var verified = false
            try {
                val snapshot = prepared ?: throw IllegalArgumentException("The prepared backup expired. Save a new backup.")
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output -> snapshot.file.inputStream().use { it.copyTo(output) } }
                        ?: throw IOException("No output stream")
                }
                state.value = State("Checking the saved file…", working = true)
                withContext(Dispatchers.IO) {
                    val expected = snapshot.file.inputStream().use(::digest)
                    val actual = context.contentResolver.openInputStream(uri)?.use(::digest) ?: throw IOException("No verification stream")
                    require(MessageDigest.isEqual(expected, actual)) { "The saved file is incomplete. Please save a new backup." }
                }
                verified = true
                val name = withContext(Dispatchers.IO) { documentName(uri) } ?: snapshot.name
                val p = snapshot.preview
                try { settings.text("lastBackup", Saved(name, System.currentTimeMillis(), snapshot.file.length(), p.counts["podcasts"] ?: 0, p.episodes, p.moments).json()) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { return@operation State("Saved and checked. Last saved file details could not be stored on this phone. Keep the new file; your provider manages cloud sync.", error = true) }
                State("Saved and checked. Your file provider manages upload and cloud sync; check Google Drive before relying on another device.")
            } catch (error: Exception) {
                // ACTION_CREATE_DOCUMENT returned a new document owned by this operation, never an existing backup.
                if (!verified) withContext(NonCancellable + Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) } }
                throw error
            }
        }
    }

    fun pickerUnavailable() {
        operation("Closing file picker…") { State("Android’s file picker is unavailable. Open your file provider and try again.", error = true) }
    }

    fun preview(uri: Uri?) {
        if (uri == null || state.value.working || state.value.awaitingDestination) return
        operation("Checking backup format, integrity and library records…") {
            val preview = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { library.preview(it) } ?: throw IOException("No input stream")
            }
            State(preview = preview)
        }
    }

    fun dismissPreview() { if (!state.value.working) state.value = State() }

    fun restore(restorePreferences: Boolean) {
        val preview = state.value.preview ?: return
        operation("Merging missing library records…") {
            val inserted = library.merge(preview) // One Room transaction; existing records always win.
            val warnings = mutableListOf<String>()
            if (restorePreferences && preview.preferences != null) {
                try { settings.restorePortable(preview.preferences) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { warnings += "Preferences could not be applied. Import again to retry them." }
            }
            try { synchronizeQueue() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { warnings += "Reopen DistillPod to refresh the player queue." }
            State("Restored $inserted records. Existing items were kept. No playback, downloads or AI work were started." +
                warnings.joinToString(" ", prefix = if (warnings.isEmpty()) "" else " "), error = warnings.isNotEmpty())
        }
    }

    private fun documentName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), android.os.Bundle.EMPTY, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.filterNot(Char::isISOControl)?.take(200) else null
        }
    }.getOrNull()

    private fun digest(input: java.io.InputStream): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var bytes = 0L
        while (true) {
            val count = input.read(buffer); if (count < 0) break
            bytes += count; require(bytes <= 32 * 1024 * 1024) { "Backup exceeds 32 MB" }
            hash.update(buffer, 0, count)
        }
        return hash.digest()
    }
}
