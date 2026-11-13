package dev.distillpod.android.data

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

data class DownloadProgress(val bytes: Long, val total: Long, val etag: String?, val lastModified: String?)
data class DownloadResult(val bytes: Long, val sha256: String, val etag: String?, val lastModified: String?)

const val DOWNLOAD_SIZE_MISMATCH = "The download size does not match the expected file."
const val STORAGE_LIMIT_MESSAGE = "Not enough room under the audio storage limit. In Settings → Storage, tap Clear unused audio, delete downloads or raise the limit, then retry."

/**
 * The download does not fit the remaining audio quota. Leftover partials and detached audio count
 * against it, so the message points to the Storage settings that free space. Not retried automatically.
 */
class StorageLimitExceeded : IllegalArgumentException(STORAGE_LIMIT_MESSAGE)

/** A partial is reusable only with a validator; completion requires a validated body and optional hash. */
class ResumableDownloader(private val http: OkHttpClient) {
    companion object { private val executor = Executors.newCachedThreadPool { Thread(it, "distillpod-download").apply { isDaemon = true } } }
    suspend fun download(url: String, partial: File, destination: File, etag: String? = null, lastModified: String? = null,
        expectedSha256: String? = null, expectedBytes: Long? = null, maximumBytes: Long = 2L * 1024 * 1024 * 1024,
        rangeChunkBytes: Long? = null,
        progress: (DownloadProgress) -> Unit = {}): DownloadResult {
        require(rangeChunkBytes == null || rangeChunkBytes in 1..10L * 1024 * 1024)
        val finished = CompletableDeferred<Unit>()
        return try { suspendCancellableCoroutine { continuation ->
        val cancelled = AtomicBoolean(false)
        val active = AtomicReference<Call?>()
        executor.submit {
            try {
                val result = transfer(url, partial, destination, etag, lastModified, expectedSha256, expectedBytes, maximumBytes, rangeChunkBytes, cancelled, active, progress)
                continuation.resume(result)
            } catch (error: Exception) { continuation.resumeWithException(error) }
            finally { finished.complete(Unit) }
        }
        continuation.invokeOnCancellation { cancelled.set(true); active.get()?.cancel() }
        } } finally {
            // Keep the caller's file lock until the writer has closed its stream.
            withContext(NonCancellable) { finished.await() }
        }
    }

    private fun transfer(url: String, partial: File, destination: File, savedEtag: String?, savedModified: String?,
        expectedHash: String?, expectedBytes: Long?, maximumBytes: Long, rangeChunkBytes: Long?, cancelled: AtomicBoolean, active: AtomicReference<Call?>,
        progress: (DownloadProgress) -> Unit): DownloadResult {
        partial.parentFile?.mkdirs(); destination.parentFile?.mkdirs()
        fun checkActive() { if (cancelled.get()) throw CancellationException("Download paused") }
        var etag = savedEtag; var modified = savedModified
        repeat(2) attempt@{ attempt ->
            checkActive()
            val validator = etag?.takeUnless { it.startsWith("W/") } ?: modified
            if (partial.exists() && validator == null) partial.delete()
            var rangeTotal: Long? = null
            while (true) {
                checkActive()
                val offset = if (partial.isFile) partial.length() else 0L
                val validator = etag?.takeUnless { it.startsWith("W/") } ?: modified
                val request = Request.Builder().url(publicHttpUrl(url)).apply {
                    if (rangeChunkBytes != null) {
                        val end = minOf(offset + rangeChunkBytes, rangeTotal ?: Long.MAX_VALUE) - 1
                        header("Range", "bytes=$offset-$end")
                        if (offset > 0 && validator != null) header("If-Range", validator)
                    } else if (offset > 0) { header("Range", "bytes=$offset-"); header("If-Range", validator!!) }
                    header("Accept-Encoding", "identity")
                }.build()
                val call = http.newCall(request); active.set(call)
                if (cancelled.get()) call.cancel()
                call.execute().use { response ->
                    if (response.code == 416 && attempt == 0) { partial.delete(); etag = null; modified = null; return@attempt }
                    if (!response.isSuccessful) throw IOException("Audio download failed (${response.code})")
                    val body = response.body ?: throw IOException("Empty download response")
                    val newEtag = response.header("ETag"); val newModified = response.header("Last-Modified")
                    val range = response.header("Content-Range")?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(it) }
                    val append = offset > 0 && response.code == 206
                    if (response.code == 206) {
                        val valid = range != null && range.groupValues[1].toLong() == offset &&
                            range.groupValues[2].toLong() >= offset && range.groupValues[3].toLong() > range.groupValues[2].toLong() &&
                            (rangeChunkBytes == null || range.groupValues[2].toLong() - offset < rangeChunkBytes) &&
                            (rangeTotal == null || rangeTotal == range.groupValues[3].toLong()) &&
                            (etag == null || newEtag == null || etag == newEtag) &&
                            (etag != null || modified == null || newModified == null || modified == newModified) &&
                            (body.contentLength() < 0 || body.contentLength() == range.groupValues[2].toLong() - offset + 1)
                        if (!valid) {
                            partial.delete(); etag = null; modified = null
                            if (attempt == 0) return@attempt
                            throw IOException("Server returned an invalid resumed download")
                        }
                    }
                    val initial = if (append) offset else 0L
                    val total = range?.groupValues?.get(3)?.toLong() ?: body.contentLength().takeIf { it >= 0 }?.let { initial + it } ?: 0
                    if (response.code == 206) rangeTotal = total
                    require(expectedBytes == null || total == 0L || total == expectedBytes) { DOWNLOAD_SIZE_MISMATCH }
                    if (total > maximumBytes) throw StorageLimitExceeded()
                    if (total > 0 && partial.parentFile!!.usableSpace < total - initial + 8_000_000) throw IOException("Not enough free storage")
                    etag = newEtag; modified = newModified
                    var bytes = initial
                    if (!append && partial.exists()) java.io.FileOutputStream(partial, false).close()
                    progress(DownloadProgress(bytes, total, etag, modified))
                    var notified = System.nanoTime()
                    body.byteStream().use { input -> java.io.FileOutputStream(partial, append).use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            checkActive()
                            val count = input.read(buffer); if (count < 0) break
                            bytes += count
                            require(expectedBytes == null || bytes <= expectedBytes) { DOWNLOAD_SIZE_MISMATCH }
                            if (bytes > maximumBytes) throw StorageLimitExceeded()
                            output.write(buffer, 0, count)
                            if (System.nanoTime() - notified >= 500_000_000) { progress(DownloadProgress(bytes, total, etag, modified)); notified = System.nanoTime() }
                        }
                        output.fd.sync()
                    } }
                    checkActive()
                    if (rangeChunkBytes != null && response.code == 206) {
                        if (bytes != range!!.groupValues[2].toLong() + 1) throw IOException("Media range ended before completion")
                        if (bytes < total) return@use
                    }
                    if (bytes == 0L || (total > 0 && bytes != total) || (expectedBytes != null && bytes != expectedBytes)) throw IOException("Download was interrupted before completion")
                    val digest = MessageDigest.getInstance("SHA-256")
                    partial.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) { checkActive(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                    }
                    val sha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (expectedHash != null && !sha.equals(expectedHash, true)) { partial.delete(); throw IOException("Downloaded file failed its integrity check") }
                    checkActive()
                    check(partial.renameTo(destination)) { "Could not finalize downloaded file" }
                    progress(DownloadProgress(bytes, bytes, etag, modified))
                    return DownloadResult(bytes, sha, etag, modified)
                }
            }
        }
        throw IOException("Could not safely resume the download; please retry")
    }
}
