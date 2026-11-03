package dev.distillpod.android.data

import android.content.Context
import androidx.room.withTransaction
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ModelSpec(val id: String, val title: String, val bytes: Long, val sha256: String) {
    val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-$id.bin"
}
val whisperModels = listOf(
    ModelSpec("tiny", "Tiny · faster", 77691713, "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"),
    ModelSpec("base", "Base · larger model", 147951465, "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"),
)

class ActiveFiles {
    private val processing = ConcurrentHashMap<String, AtomicInteger>()
    @Volatile var playback: String? = null
    @Volatile var queued: Set<String> = emptySet()
    fun busy(path: String): Boolean = path == playback || path in queued || (processing[path]?.get() ?: 0) > 0
    fun acquire(file: File) { processing.getOrPut(file.absolutePath) { AtomicInteger() }.incrementAndGet() }
    fun release(file: File) { processing[file.absolutePath]?.decrementAndGet() }
}

class MediaRepository(context: Context, private val db: AppDatabase, private val settings: AppSettings,
    private val downloader: ResumableDownloader, val active: ActiveFiles) {
    private val dao = db.library()
    val directory = File(context.filesDir, "media").apply { mkdirs() }
    val modelsDirectory = File(context.filesDir, "models").apply { mkdirs() }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val episodeTransfers = Mutex()

    suspend fun ensureDownloaded(id: String, notify: (DownloadProgress) -> Unit = {}): File = episodeTransfers.withLock { downloadEpisode(id, notify) }

    private suspend fun downloadEpisode(id: String, notify: (DownloadProgress) -> Unit): File = locks.getOrPut("episode:$id") { Mutex() }.withLock {
        val ep = dao.episode(id) ?: error("Episode is no longer available")
        ep.localFile?.let(::File)?.takeIf { it.isFile && it.length() == ep.downloadedBytes && ep.downloadState == "downloaded" }?.let { return@withLock it }
        val prefs = settings.flow.first()
        val otherBytes = withContext(Dispatchers.IO) { directory.listFiles()?.filter { it.name != "$id.partial" }?.sumOf { it.length() } ?: 0 }
        val partial = File(directory, "$id.partial")
        val target = File(directory, "$id-${UUID.randomUUID()}.audio")
        try {
            val result = downloader.download(ep.audioUrl, partial, target, ep.etag, ep.lastModified,
                maximumBytes = (prefs.storageLimitBytes - otherBytes).coerceAtLeast(0)) { progress ->
                runBlocking { dao.downloadProgress(id, "downloading", progress.bytes, progress.total, progress.etag, progress.lastModified, null) }
                notify(progress)
            }
            db.withTransaction {
                val latest = dao.episode(id) ?: error("Episode is no longer available")
                require(latest.audioUrl == ep.audioUrl) { "The episode audio changed during download. Please retry." }
                dao.downloadComplete(id, target.absolutePath, result.sha256, result.bytes)
                if (latest.mediaFingerprint != result.sha256) {
                    dao.transcript(id)?.let { dao.putTranscript(it.copy(state = "stale", error = "Audio changed. Prepare the transcript again.")) }
                    dao.analysis(id)?.let { dao.putAnalysis(it.copy(state = "stale", error = "Audio changed. Analyze the new transcript.")) }
                }
            }
            target
        } catch (error: Exception) {
            withContext(NonCancellable) {
                if (dao.episode(id)?.localFile != target.absolutePath) target.delete()
                val latest = dao.episode(id)
                if (latest != null) dao.downloadProgress(id, if (error is CancellationException) "paused" else "failed", partial.length(), latest.totalBytes,
                    latest.etag, latest.lastModified, if (error is CancellationException) null else safeFailure(error))
            }
            throw error
        }
    }

    suspend fun deleteDownload(id: String) = withContext(Dispatchers.IO) {
        locks.getOrPut("episode:$id") { Mutex() }.withLock {
            val ep = dao.episode(id) ?: return@withLock
            val file = ep.localFile?.let(::File)
            require(file == null || !active.busy(file.absolutePath)) { "This audio is playing or being transcribed. Stop that work before deleting it." }
            if (file != null) check(!file.exists() || file.delete()) { "Could not delete the audio file" }
            File(directory, "$id.partial").delete()
            dao.removeDownload(id)
        }
    }

    suspend fun clearUnusedAudio(): Long = episodeTransfers.withLock { withContext(Dispatchers.IO) {
        val episodes = dao.episodes()
        val known = episodes.mapNotNull { it.localFile }.toSet() + episodes.filter { it.downloadState in listOf("paused", "queued", "downloading") }
            .map { File(directory, "${it.id}.partial").absolutePath }
        var bytes = 0L
        directory.listFiles()?.filter { it.isFile && it.absolutePath !in known && !active.busy(it.absolutePath) }?.forEach { file ->
            val length = file.length(); if (file.delete()) bytes += length
        }
        bytes
    } }

    suspend fun downloadModel(id: String, notify: (DownloadProgress) -> Unit = {}): File = locks.getOrPut("model:$id") { Mutex() }.withLock {
        val spec = whisperModels.firstOrNull { it.id == id } ?: error("Unknown transcription model")
        val existing = dao.model(id)
        val partial = File(modelsDirectory, "$id.partial")
        val target = File(modelsDirectory, "$id.bin")
        try {
            downloader.download(spec.url, partial, target, etag = existing?.etag, expectedSha256 = spec.sha256, expectedBytes = spec.bytes, maximumBytes = spec.bytes) { progress ->
                runBlocking { dao.putModel(ModelEntity(id, "downloading", progress.bytes, progress.etag)) }
                notify(progress)
            }
            dao.putModel(ModelEntity(id, "ready", spec.bytes))
            target
        } catch (error: Exception) {
            withContext(NonCancellable) { dao.putModel(ModelEntity(id, if (error is CancellationException) "paused" else "failed", partial.length(), dao.model(id)?.etag, safeFailure(error))) }
            throw error
        }
    }

    suspend fun verifiedModel(id: String): File = withContext(Dispatchers.IO) {
        val spec = whisperModels.firstOrNull { it.id == id } ?: error("Choose a transcription model in Settings")
        val file = File(modelsDirectory, "$id.bin")
        require(file.isFile && file.length() == spec.bytes && dao.model(id)?.state == "ready") { "Download the transcription model in Settings first" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(65536); while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) } }
        require(digest.digest().joinToString("") { "%02x".format(it) } == spec.sha256) { "Model integrity check failed. Delete and download it again." }
        file
    }

    suspend fun deleteModel(id: String) = withContext(Dispatchers.IO) {
        locks.getOrPut("model:$id") { Mutex() }.withLock {
            val file = File(modelsDirectory, "$id.bin")
            require(!active.busy(file.absolutePath)) { "This model is currently transcribing audio" }
            check(!file.exists() || file.delete())
            File(modelsDirectory, "$id.partial").delete(); dao.putModel(ModelEntity(id))
        }
    }
}

fun safeFailure(error: Throwable): String = when (error) {
    is dev.distillpod.android.QuotaExceeded -> "ChatGPT allowance is unavailable. Retry when your plan allows more work."
    is dev.distillpod.android.ReauthorizationRequired -> "Reconnect ChatGPT in Settings, then retry."
    is java.net.UnknownHostException -> "Could not reach the server. Check your connection and retry."
    is IOException -> "The transfer was interrupted. Check storage and connection, then retry."
    is IllegalArgumentException -> error.message?.takeIf { !it.contains("http") && it.length <= 180 } ?: "The supplied data could not be validated."
    else -> "The operation could not finish. Please retry."
}
