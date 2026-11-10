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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ModelSpec(val id: String, val title: String, val bytes: Long, val sha256: String) {
    val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-$id.bin"
}
val whisperModels = listOf(
    ModelSpec("tiny", "Tiny · faster", 77691713, "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"),
    ModelSpec("base", "Base · larger model", 147951465, "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"),
)

data class AudioStorageUsage(
    val quotaBytes: Long,
    val downloadedBytes: Long,
    val partialCacheBytes: Long,
    val modelBytes: Long,
)

class ActiveFiles {
    private val processing = ConcurrentHashMap<String, AtomicInteger>()
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    @Volatile var playback: String? = null
        set(value) { if (field != value) { field = value; revision.update { it + 1 } } }
    @Volatile var queued: Set<String> = emptySet()
        set(value) { if (field != value) { field = value; revision.update { it + 1 } } }
    fun busy(path: String): Boolean = path == playback || path in queued || (processing[path]?.get() ?: 0) > 0
    fun acquire(file: File) { processing.getOrPut(file.absolutePath) { AtomicInteger() }.incrementAndGet(); revision.update { it + 1 } }
    fun release(file: File) { processing[file.absolutePath]?.decrementAndGet(); revision.update { it + 1 } }
}

class MediaRepository(context: Context, private val db: AppDatabase, private val settings: AppSettings,
    private val downloader: ResumableDownloader, val active: ActiveFiles,
    private val resolveAudio: (String) -> String = { it },
    private val onValidated: () -> Unit = {}) {
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
        val otherBytes = withContext(Dispatchers.IO) { audioFiles().filter { it.name != "$id.partial" }.sumOf { it.length() } }
        val partial = File(directory, "$id.partial")
        val target = File(directory, "$id-${UUID.randomUUID()}.audio")
        try {
            val youtube = dev.distillpod.core.YouTubeUrl.videoId(ep.audioUrl) != null
            val resolved = runInterruptible(Dispatchers.IO) { resolveAudio(ep.audioUrl) }
            // A fresh extraction can select a different representation; never append old bytes.
            if (youtube) withContext(Dispatchers.IO) { partial.delete() }
            val result = downloader.download(resolved, partial, target, if (youtube) null else ep.etag, if (youtube) null else ep.lastModified,
                maximumBytes = (prefs.storageLimitBytes - otherBytes).coerceAtLeast(0),
                rangeChunkBytes = if (youtube) dev.distillpod.android.video.YOUTUBE_RANGE_BYTES else null) { progress ->
                runBlocking { dao.downloadProgress(id, "downloading", progress.bytes, progress.total, progress.etag, progress.lastModified, null) }
                notify(progress)
            }
            if (youtube) withContext(Dispatchers.IO) {
                val extractor = android.media.MediaExtractor()
                try {
                    extractor.setDataSource(target.absolutePath)
                    require((0 until extractor.trackCount).any {
                        extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    }) { "YouTube returned an invalid audio file. Please retry." }
                } finally { extractor.release() }
            }
            db.withTransaction {
                val latest = dao.episode(id) ?: error("Episode is no longer available")
                require(latest.audioUrl == ep.audioUrl) { "The episode audio changed during download. Please retry." }
                dao.downloadComplete(id, target.absolutePath, result.sha256, result.bytes)
                if (latest.mediaFingerprint != result.sha256) {
                    dao.transcript(id)?.let { dao.putTranscriptAt(it.copy(state = "stale", error = "Audio changed. Prepare the transcript again."), TranscriptWriteSite.DOWNLOAD_FINGERPRINT) }
                    dao.analysis(id)?.let { dao.putAnalysis(it.copy(state = "stale", error = "Audio changed. Analyze the new transcript.")) }
                }
            }
            if (youtube) runCatching { onValidated() }
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

    // Use the same file set for quota enforcement and Settings; Room byte counts can be stale.
    private fun audioFiles(): List<File> = directory.listFiles()?.filter { it.isFile }.orEmpty()

    suspend fun storageUsage(): AudioStorageUsage = withContext(Dispatchers.IO) {
        val completed = dao.episodes().filter { it.downloadState == "downloaded" }.mapNotNull { it.localFile }.toSet()
        val sizes = audioFiles().map { it.absolutePath to it.length() }
        val quota = sizes.sumOf { it.second }
        val downloaded = sizes.filter { it.first in completed }.sumOf { it.second }
        AudioStorageUsage(quota, downloaded, quota - downloaded,
            modelsDirectory.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0)
    }

    private suspend fun requireRemovable(ep: EpisodeEntity, partial: File) {
        require(partial.canonicalFile.parentFile == directory.canonicalFile) { "Invalid audio path" }
        val file = ep.localFile?.let(::File)
        require(file == null || file.canonicalFile.parentFile == directory.canonicalFile) { "Invalid audio path" }
        deletionBlock(ep, dao.queue().any { it.episodeId == ep.id }, dao.transcript(ep.id)?.state)
            ?.let { throw AudioDeletionBlocked(it) }
    }

    // Readiness is advisory; deletion always repeats these checks under its existing locks/transaction.
    fun deletionBlock(ep: EpisodeEntity, queued: Boolean, transcriptState: String?): AudioDeletionBlockReason? {
        val paths = listOfNotNull(ep.localFile, File(directory, "${ep.id}.partial").absolutePath)
        return audioDeletionBlockReason(
            active = paths.any(active::busy),
            queued = queued || paths.any { it in active.queued },
            transcribing = transcriptState in listOf("queued", "processing"),
            downloading = ep.downloadState in listOf("queued", "downloading"),
        )
    }

    suspend fun discardPausedPartial(id: String): Long = episodeTransfers.withLock {
        locks.getOrPut("episode:$id") { Mutex() }.withLock {
            withContext(Dispatchers.IO) {
                db.withTransaction {
                    val ep = dao.episode(id) ?: error("Episode is no longer available")
                    val partial = File(directory, "$id.partial")
                    requireRemovable(ep, partial)
                    require(ep.downloadState == "paused" && ep.localFile == null) { "Only a paused partial download can be discarded." }
                    val bytes = partial.length()
                    check(!partial.exists() || partial.delete()) { "Could not discard the partial download" }
                    dao.downloadProgress(id, "none", 0, 0, null, null, null)
                    bytes
                }
            }
        }
    }

    suspend fun deleteDownload(id: String) = episodeTransfers.withLock {
        withContext(Dispatchers.IO) {
            locks.getOrPut("episode:$id") { Mutex() }.withLock {
                db.withTransaction {
                    val ep = dao.episode(id) ?: return@withTransaction
                    val partial = File(directory, "$id.partial")
                    requireRemovable(ep, partial)
                    val file = ep.localFile?.let(::File)
                    // A failed partial cleanup must not remove the completed audio first.
                    check(!partial.exists() || partial.delete()) { "Could not delete the partial download" }
                    if (file != null) check(!file.exists() || file.delete()) { "Could not delete the audio file" }
                    dao.removeDownload(id)
                    dao.downloadProgress(id, "none", 0, 0, null, null, null)
                }
            }
        }
    }

    suspend fun clearUnusedAudio(): Long = episodeTransfers.withLock { withContext(Dispatchers.IO) {
        val episodes = dao.episodes()
        val protectedIds = dao.queue().map { it.episodeId }.toSet() +
            listOfNotNull(dao.playerSession()?.episodeId) +
            dao.transcripts().filter { it.state in listOf("queued", "processing") }.map { it.episodeId }
        val known = episodes.mapNotNull { it.localFile }.toSet() + episodes.filter {
            it.downloadState in listOf("paused", "queued", "downloading") || it.id in protectedIds
        }
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
